# Ignition — accounts, zone membership, and PostgreSQL persistence

Status: **proposed** — awaiting approval, then implemented in the steps at the
end.

## Why

Today every human authenticates with a static bearer token, and all state is a
tree of `.env` files under `state/`. This change:

- Adds **email / password accounts** with email-verified activation and
  admin-approved self-signup.
- Adds **zone membership** — a zone *is* the team; each zone has members and at
  least one **zone admin**. No separate "team" entity.
- Adds **public / private zones** — private zones are visible only to their
  members, platform admins, and an email allow-list.
- Removes `IGN_ADMIN_TOKEN` and `zone-token`; only the per-zone
  **`deploy-token`** survives, for CI.
- **Moves all persistence to PostgreSQL** — nodes, zones, apps, provisioning
  status, secrets, and the new identity tables. The `state/` file tree stops
  being a source of truth (see [Rendered artifacts](#rendered-artifacts-vs-source-of-truth)).

## Roles

| role | scope | can do |
|---|---|---|
| **platform admin** | global | approve signups; promote/demote other platform admins; register/drain nodes; roster; idle sweep; **create / destroy any zone**; assign a zone's first admin; everything a zone admin can, for any zone |
| **zone admin** | one zone | add/remove **approved** users to/from *their* zone; promote/demote any member of that zone as zone admin; set the zone `PUBLIC`/`PRIVATE` and manage its viewer allow-list; full zone-console access (Forgejo users, repos, releases, apps, runner, status); destroy *their* zone |
| **member** | one zone | zone-console access for *their* zone |
| **CI** (not a person) | one zone | `POST /deploy`, `POST /undeploy` with that zone's `deploy-token` |

- A user can be a member of **multiple zones**, with a different role
  (`MEMBER` / `ZONE_ADMIN`) per zone. Granted authorities are the **union** over
  every zone they're in, plus `PLATFORM_ADMIN` if the flag is set.
- A zone can have **multiple zone admins**; there can be **multiple platform
  admins**. A last-admin guard applies to both: the sole zone admin of a zone
  can't demote/remove themselves, and the sole platform admin can't clear their
  own flag.
- **Promotion.** A platform admin sets/clears `is_platform_admin` on any other
  `ACTIVE` user. A zone admin (or any platform admin) sets a zone member's role
  to `ZONE_ADMIN` or back to `MEMBER`.
- A user with no zone membership can sign in but only sees "you're not in a zone
  yet".

## Account lifecycle

```
                    ┌──────────────────┐        admin approves,
   self-signup ───► │ PENDING_APPROVAL │        activation email sent
                    └────────┬─────────┘        (sets preapproved)
                             │
                    ┌────────▼──────────────┐
   admin invite ──► │ PENDING_VERIFICATION  │
                    └────────┬───────────────┘
              click link, set password
                    ┌────────▼─┐
                    │  ACTIVE  │
                    └────┬─────┘
     admin disables      │
                    ┌────▼─────┐
                    │ DISABLED │
                    └──────────┘
```

- **Self-signup** (`/signup`): email → user `PENDING_APPROVAL`, no roles, **no
  mail sent** — a platform admin has to look at the request first. They
  approve it → the account is marked `preapproved` and moves to
  `PENDING_VERIFICATION`, *now* the activation email goes out → click link,
  set password → `ACTIVE` straight away (the approval already happened; no
  second gate). Still in no zone. The email is deliberately withheld until
  approval — nothing is sent for a request nobody's looked at yet.
- **Admin invite** (from a zone's members page, or the users page): email →
  user `PENDING_VERIFICATION`, `preapproved` from creation (+ a pending zone
  membership if invited into a zone) → activation email sent immediately →
  click link, set password → `ACTIVE` (no approval step — an admin already
  initiated it). If the email already belongs to an `ACTIVE` user, "invite
  into zone" just adds the membership.
- **Password reset** (`/forgot`): email → reset link (same token table,
  `purpose = RESET`) → set new password.
- **Disable / re-enable**: platform admin only. A `DISABLED` user can't log in;
  sessions are dropped on next request.

## First run (bootstrap)

`ignition-control` starts with an empty `app_user` table.

- On startup, if `count(app_user) = 0`, it generates a random **bootstrap
  code** and logs it as `WARN`, repeating every 30 s until claimed:
  `IGNITION SETUP — open <IGN_PUBLIC_URL>/setup and enter code: <code>`.
- `GET /setup` is reachable **only while there are zero users**; otherwise 404.
- The form takes the **admin email** + the **bootstrap code**. On submit: create
  the user `PENDING_VERIFICATION`, `is_platform_admin = true`, send the
  activation email, invalidate the code. Click the link, set a password →
  `ACTIVE`. No approval.
- After the first user exists, `/setup` is dead; onboarding is `/signup` +
  approval, or admin invite.

The code (rather than "just ask for an email") matters because `/setup` is
unauthenticated and internet-reachable via the demo forwarder; the code only
appears in the operator's container logs.

## Zone visibility (public / private)

Every zone has a `visibility`. **All zones are `PUBLIC` for now** — the default,
and until a zone admin changes it the behaviour is exactly as today:
`git.<slug>.<BASE_DOMAIN>` and every `<app>.apps.<slug>.<BASE_DOMAIN>` are open
to anyone.

Setting a zone **`PRIVATE`** puts an auth check in front of those published
surfaces (not the zone console — that's always members-only). A request is
allowed if the caller is signed in **and** any of:

- a member (any role) of that zone, or
- a platform admin, or
- their email is on the zone's viewer allow-list (`zone_viewer`).

The zone admin manages `visibility` and the allow-list. Allow-listed people
still need an Ignition account (they `/signup` and are approved, or are invited)
— there is no anonymous access to a private zone.

**Mechanism.** `ignition-control` already writes the per-zone Traefik router
snippet (`state/control/dynamic/<slug>.yml`). For a `PRIVATE` zone it adds a
`forwardAuth` middleware on the `git.<slug>` and `*.apps.<slug>` routers,
pointing at `ignition-control`'s own `GET /authz/zone/{slug}` — which reads the
session cookie and returns `204` (allow) or `401`/`403` (deny; a browser is
redirected to `/login`). `PUBLIC` → no middleware. Toggling rewrites the
snippet; Traefik reloads on its file watch, no restart.

For the session cookie to reach `git.<slug>` and `<app>.apps.<slug>` it is
issued `Domain=.<BASE_DOMAIN>; Secure; HttpOnly; SameSite=Lax`.

`docker push` / `git` over HTTP still work against a private zone: `forwardAuth`
bypasses requests with HTTP Basic / a git or registry user-agent, and Forgejo
authenticates them with a personal access token (unchanged from the deploy flow).

## Data model (PostgreSQL)

New dependency: **PostgreSQL**, via **Spring Data JPA**; schema by **Flyway**
(`V1__schema.sql`). **All** records live here — the existing infra state as well
as the new identity tables.

### Infra (replaces the `state/*.env` files)

```sql
create table node (
  name        text primary key,                  -- ^[a-z0-9][a-z0-9-]{0,38}[a-z0-9]$
  docker_host text not null,                      -- local | unix://… | ssh://… | tcp://…
  cpus        double precision not null,
  mem_gb      double precision not null,
  labels      text[] not null default '{}',
  state       text not null default 'ACTIVE',     -- ACTIVE | DRAINING
  created_at  timestamptz not null default now()
);

create table zone (
  slug          text primary key,                -- ^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$
  node_name     text not null references node(name) on delete restrict,
  base_domain   text not null,
  zone_cpus     double precision not null,
  zone_mem_gb   double precision not null,
  visibility    text not null default 'PUBLIC',   -- PUBLIC | PRIVATE
  last_activity timestamptz not null default now(),
  created_by    uuid references app_user(id),
  created_at    timestamptz not null default now()
);

create table zone_secret (                        -- runner-secret, forgejo url/token, deploy-token, …
  zone_slug  text not null references zone(slug) on delete cascade,
  name       text not null,
  value      text not null,                       -- see "Secrets at rest" below
  primary key (zone_slug, name)
);

create table app (
  zone_slug  text not null references zone(slug) on delete cascade,
  name       text not null,                       -- ^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$
  image      text not null,
  port       integer not null,
  deploy_id  text not null,
  deployed_at timestamptz not null default now(),
  primary key (zone_slug, name)
);

create table provisioning_status (
  zone_slug  text primary key,
  state      text not null,                       -- RUNNING | DONE | FAILED
  message    text,
  updated_at timestamptz not null default now()
);
```

`Node`, `Zone`, `DeployedApp` become `@Entity`; `NodeRepository`,
`ZoneRepository`, `AppRepository` become `JpaRepository`. `EnvFile`,
`Node.fromEnv/toEnv`, `Zone.fromEnv`, `Zone*Repository` file-walking, and
`state/…` path helpers in `IgnitionProperties` are deleted. `ProvisioningService`
writes rows instead of `.env` files; teardown is `DELETE FROM zone` (cascades)
plus `docker compose down -v`.

### Identity

```sql
create extension if not exists citext;

create table app_user (
  id                uuid primary key default gen_random_uuid(),
  email             citext unique not null,
  password_hash     text,                        -- null until activated
  status            text not null,               -- PENDING_VERIFICATION|PENDING_APPROVAL|ACTIVE|DISABLED
  is_platform_admin boolean not null default false,
  created_at        timestamptz not null default now(),
  activated_at      timestamptz
);

create table auth_token (
  token_hash  text primary key,                  -- sha-256 of the random token; raw token only in the email
  user_id     uuid not null references app_user(id) on delete cascade,
  purpose     text not null,                      -- ACTIVATE | RESET
  expires_at  timestamptz not null,
  used_at     timestamptz
);

create table zone_member (
  zone_slug  text not null references zone(slug) on delete cascade,
  user_id    uuid not null references app_user(id) on delete cascade,
  role       text not null,                       -- MEMBER | ZONE_ADMIN
  added_at   timestamptz not null default now(),
  primary key (zone_slug, user_id)
);

create table zone_viewer (                        -- extra allowed viewers of a PRIVATE zone
  zone_slug  text not null references zone(slug) on delete cascade,
  email      citext not null,
  added_by   uuid references app_user(id),
  added_at   timestamptz not null default now(),
  primary key (zone_slug, email)
);
```

- Auth tokens: 32 random bytes, base64url in the link; only the **sha-256**
  stored. TTL 24 h (activation) / 1 h (reset). A new one deletes the prior
  unused token for that `(user, purpose)`.
- Passwords: **bcrypt** via `DelegatingPasswordEncoder` (`{bcrypt}`), cost 12.

### Secrets at rest

`zone_secret` holds credentials (Forgejo admin token, runner secret,
`deploy-token`). They are encrypted with AES-GCM using a key from
**`IGN_SECRET_KEY`** (32 bytes, base64; required at startup) via a small
`AttributeConverter`, so a `pg_dump` isn't a plaintext credential dump. Losing
the key means re-provisioning zones, which is acceptable for this system.

## Rendered artifacts vs. source of truth

PostgreSQL is the source of truth. Some files still get written to disk because
external tools read them — but they are **regenerated from the DB**, never
edited, and safe to delete:

| file | consumer | when written |
|---|---|---|
| `<work>/zones/<slug>/docker-compose.yml` | `docker compose` | before each compose call for the zone |
| `<work>/zones/<slug>/apps/<name>-compose.yml` | `docker compose` | before each app compose call |
| `<work>/zones/<slug>/runner-config.yml` | `docker compose cp` into the runner | at provisioning |
| `<work>/control/dynamic/*.yml` | Traefik file provider | on any router/visibility change, and on startup (full rebuild from DB) |

`IGN_STATE_DIR` becomes **`IGN_WORK_DIR`** (default `/work`), an ephemeral
scratch/cache dir — a `tmpfs` or a throwaway volume. On startup
`ignition-control` rebuilds `control/dynamic/` from `node`/`zone` rows so
Traefik is correct even on a fresh work dir. Backups are now a single
`pg_dump`.

## SMTP (required)

The service **fails fast on startup** if SMTP isn't configured — no "link in the
logs" fallback.

```
IGN_SMTP_HOST      (required)
IGN_SMTP_PORT      (default 587)
IGN_SMTP_USERNAME  (required)
IGN_SMTP_PASSWORD  (required)
IGN_SMTP_FROM      (required, e.g. "Ignition <ignition@classesarecode.net>")
IGN_SMTP_STARTTLS  (default true)
IGN_PUBLIC_URL     (required, e.g. https://ignition.classesarecode.net — builds links)
```

Also required at startup: **`IGN_SECRET_KEY`** (32 bytes base64, for
`zone_secret` encryption) and **`SPRING_DATASOURCE_*`**. `IGN_STATE_DIR` is
replaced by **`IGN_WORK_DIR`** (default `/work`, ephemeral).

`spring-boot-starter-mail`; a `@Validated @ConfigurationProperties` class with
`@NotBlank` on the required fields so a missing value is a clear startup
failure. Plain-text templates: activate, approved, reset.

## Security configuration

`SecurityConfig` is rewritten:

- **Form login** (`/login`) → servlet session. Cookie
  `Domain=.<BASE_DOMAIN>; Secure; HttpOnly; SameSite=Lax` so it also reaches
  `git.<slug>` / `<app>.apps.<slug>` for private-zone `forwardAuth`.
  `UserDetailsService` loads `app_user` by email; authorities =
  `PLATFORM_ADMIN` (if flag) + `ZONE_ADMIN:<slug>` / `MEMBER:<slug>` per
  membership. `DISABLED`/`PENDING_*` → login refused with a specific message.
- **`TokenAuthenticationFilter` shrinks to CI only** — runs on `POST /deploy`
  and `POST /undeploy`, resolves a `Bearer` token against
  `state/zones/*/deploy-token`, yields `ROLE_DEPLOY` + slug. `TokenResolver`
  loses its PLATFORM and ZONE branches; `IgnitionProperties.adminToken` is
  removed.
- **CSRF re-enabled** for the browser app (clears the `// SCAFFOLD` TODO);
  `csrf.ignoringRequestMatchers("/deploy", "/undeploy")` (bearer-only).
- Authorization:
  - `permitAll`: `/actuator/health/**`, `/login`, `/logout`, `/setup/**`,
    `/signup`, `/activate`, `/forgot`, `/reset`, `/css/**`, `/favicon.ico`
  - `GET /authz/zone/{slug}` → `authenticated()`; the handler returns 204/403
    from membership / allow-list / visibility
  - `POST /deploy`, `/undeploy` → `hasRole("DEPLOY")`
  - `/admin/**` → `hasAuthority("PLATFORM_ADMIN")`
  - `/z/{slug}/**` → a custom `AuthorizationManager`: platform admin, or the
    right membership authority for that `slug` (`ZONE_ADMIN:<slug>` for
    management actions, `MEMBER:<slug>`+ for console reads)
  - `anyRequest().authenticated()`
- In-memory per-IP + per-email rate limiting on `/login`, `/signup`, `/forgot`,
  `/setup`. `/signup` and `/forgot` always return the same "check your inbox"
  regardless of whether the address existed.

## New / changed code

```
persistence/  (infra tables — replaces state/*.env)
  Node.java Zone.java DeployedApp.java ProvisioningStatus.java ZoneSecret.java   @Entity
  NodeRepository/ZoneRepository/AppRepository/…      now JpaRepository
  SecretCipher.java (AES-GCM AttributeConverter, key from IGN_SECRET_KEY)
  RenderService.java  writes docker-compose.yml / runner-config.yml / dynamic/*.yml from rows
  — deleted: EnvFile, *.fromEnv/toEnv, file-walking repo impls, state path helpers
auth/
  AppUser.java (+ Status enum)   AppUserRepository.java
  AuthToken.java                 AuthTokenRepository.java
  ZoneMember.java (id class)     ZoneMemberRepository.java
  ZoneViewer.java                ZoneViewerRepository.java
  AccountService.java     signup, invite, activate, approve, disable, resetPassword,
                          bootstrap, setPlatformAdmin (last-admin guard)
  ZoneAccessService.java  addMember, removeMember, setRole (last-zone-admin guard),
                          setVisibility, addViewer, removeViewer,
                          canView(slug, user) -> boolean, zonesFor(user)
  MailService.java        sendActivation / sendApproved / sendReset
  IgnitionUserDetailsService.java
  SmtpProperties.java     @Validated, required fields
security/
  SecurityConfig.java          rewritten (form login + slim deploy filter)
  DeployTokenFilter.java       trimmed TokenAuthenticationFilter
  ZoneAuthorizationManager.java
web/
  SetupController.java     GET/POST /setup                       (bootstrap)
  SignupController.java    /signup /activate /forgot /reset
  AccountController.java   /account  (change password, my zones)
  AdminController.java     /admin  — approvals, users, platform-admin flag, zones
  ZoneMembersController.java  /z/{slug}/members  — members, roles, visibility, viewers
  AuthzController.java     GET /authz/zone/{slug}                 (forwardAuth)
  ZoneConsoleController.java     auth switched from zone-token to membership
  PlatformConsoleController.java, RosterController.java  → /admin/**, platform-admin only
  DeployController.java    unchanged (deploy-token)
  CurrentUser.java         replaces CurrentPrincipal
config/
  IgnitionProperties.java  drop adminToken + state path helpers; add publicUrl, workDir, secretKey
provisioning/
  ProvisioningService  writes DB rows (not .env); takes an initial-admin user id;
                       writes zone row (+ visibility) + a ZONE_ADMIN zone_member row
traefik/
  TraefikDynamicConfig  rebuilds dynamic/*.yml from DB on startup;
                        writeZoneRouter(slug, visibility) emits forwardAuth for PRIVATE
templates/  setup.html login.html signup.html activate.html forgot.html reset.html
            admin/approvals.html admin/users.html admin/zones.html
            z/members.html  account.html  (+ update existing console pages)
db/migration/V1__auth.sql
```

`Scheduler`, `ForgejoClient`, the `docker`/compose shell-out, and the
`app-compose`/`zone-compose` templates are unchanged. `ZoneService`,
`AppService`, `IdleSweeper` change only where they read/write state — rows
instead of files; `RenderService` produces the transient compose/dynamic files
just before the `docker` calls that need them. Teardown / sweep is
`DELETE FROM zone` (cascades to `app`, `zone_secret`, `zone_member`,
`zone_viewer`) + `docker compose down -v`.

## Compose / deployment changes

`templates/ignition-control-compose.yml`:

- **add** `postgres:16-alpine` — named volume `ignition-pgdata`,
  `POSTGRES_DB/USER/PASSWORD`, `pg_isready` healthcheck, on a new internal
  `ignition-internal` network (not `traefik-public`).
- `ignition-control` gains `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres/ignition`
  (+ user/password), `depends_on: postgres (service_healthy)`, the `IGN_SMTP_*`,
  `IGN_PUBLIC_URL`, `IGN_SECRET_KEY` env, and joins `ignition-internal`.
- **remove** `IGN_ADMIN_TOKEN`.
- the `../state:/state` bind becomes an ephemeral **`IGN_WORK_DIR`** (a
  `tmpfs` or a throwaway named volume `ignition-work`).
- the Traefik file-provider directory (`control/dynamic`) becomes a **named
  volume shared** between `ignition-control` (writer) and each node's
  `traefik-core` (reader) instead of a bind into `state/`.

`traefik-core-compose.yml`: mount the shared `ignition-dynamic` volume at
`/etc/traefik/dynamic` instead of `../state/control/dynamic`.

`pom.xml` adds `spring-boot-starter-data-jpa`, `org.postgresql:postgresql`,
`flyway-core`, `flyway-database-postgresql`, `spring-boot-starter-mail`.
No Dockerfile base-image change.

The whole `state/` directory and its `.gitkeep` / `.gitignore` entries are
removed; a `pg_dump` (plus the AES key) is the backup.

## Docs to update

- `docs/roles.md` — platform admin / zone admin / member; the approval flow.
- `docs/architecture.md` — auth-model section; zone membership; public/private.
- `docs/operating-an-event.md` — first-run `/setup` + bootstrap code; Postgres
  and SMTP prerequisites; creating a zone now also picks its first admin.
- `README.md`, `CLAUDE.md` — the model, removed tokens, new env.
- `demo-1-spitfire.md` — Postgres + SMTP required; no `IGN_ADMIN_TOKEN`;
  first-run `/setup` with the code from `docker compose logs`.
- `demo-2-remote-access.md` — provisioning a zone now assigns you as its admin;
  optional: set it private + add a viewer.
- `docs/exposure.md` — reconcile the per-app `visibility` note with the new
  per-zone `PUBLIC`/`PRIVATE` and the `ignition-control` `forwardAuth` check.
- `DESIGN.md` — the "Auth model (principals unchanged)" **and** "State" (`state/`
  file tree) sections are superseded; link here.
- New `docs/accounts.md` in the mkdocs nav.

## Decisions folded in

- Storage: **PostgreSQL for everything** — nodes, zones, apps, provisioning
  status, secrets (AES-GCM at rest), and identity. `state/` is gone; the only
  files on disk are transient renders (compose, runner config, Traefik dynamic)
  regenerated from the DB.
- Email: **SMTP required**, fail fast.
- Tokens: **`IGN_ADMIN_TOKEN` and `zone-token` removed**; `deploy-token` stays.
- **No team layer — a zone is the team.** Zone carries membership directly.
- **Many-to-many**: users ↔ zones (role per zone), multiple zone admins,
  multiple platform admins, last-admin guard on both.
- **Promotion**: platform admin ↔ platform admin; zone admin (or platform
  admin) ↔ zone admin within a zone.
- **Zone visibility**: `PUBLIC` (default, every zone today) or `PRIVATE`
  (members + platform admins + email allow-list, enforced by `forwardAuth`).
- Zone creation stays a **platform-admin** action (it places on a node); it now
  also names the zone's first admin.

## Implementation steps

1. **This doc.** ← you are here
2. **Postgres + Flyway + infra tables.** pom deps; `application.yml` datasource;
   `V1__schema.sql` (all tables); `Node`/`Zone`/`DeployedApp`/`ProvisioningStatus`
   `/ZoneSecret` entities + `JpaRepository`s; `SecretCipher`; `RenderService`.
   Convert `ProvisioningService`, `ZoneService`, `AppService`, `Scheduler`,
   `IdleSweeper`, `TraefikDynamicConfig` to rows + renders. Delete `EnvFile` and
   the `state/` tree. Testcontainers. **End-to-end provision/deploy/teardown
   still works, still token-auth.**
3. **Identity tables + accounts core.** `app_user` / `auth_token` /
   `zone_member` / `zone_viewer`; `AccountService` (signup, activate, invite,
   approve, disable, reset, setPlatformAdmin), `SmtpProperties` (fail-fast),
   `MailService` + templates. Unit tests with a stub mail sender.
4. **Security cutover.** Rewrite `SecurityConfig` → form login +
   `IgnitionUserDetailsService`; shrink the token filter to deploy-only; drop
   `adminToken`; re-enable CSRF; `ZoneAuthorizationManager`. Convert every
   controller/template from principal-kind checks to authorities. `/login`,
   `/signup`, `/activate`, `/forgot`, `/reset` pages.
5. **Bootstrap.** `SetupController` + `setup.html`; startup bootstrap-code
   logger; `/setup` gated on zero users.
6. **Zone membership.** ~~Done, partially~~ — `ZoneAccessService` (add/remove/
   setRole, last-zone-admin guard), `ZoneAuthorizationManager` gating `/z/**`
   on `MEMBER:<slug>`/`PLATFORM_ADMIN`, `ProvisioningService` taking the
   creator and writing their `ZONE_ADMIN` row, and a Members section on the
   existing team console (add is "attach an existing account", not an invite —
   see `ZoneAccessService` javadoc). **Still missing**: `AdminController`
   (approvals/users/zones — there's no UI yet to approve a `PENDING_APPROVAL`
   signup or browse all platform users); a dedicated `ZoneMembersController`/
   page (members live inline on the team console instead); `RosterController`
   is still platform-admin-only (bulk apply doesn't take a creator/admin
   picker beyond "whoever ran it"). Step 7 (visibility) untouched.
7. **Zone visibility.** `visibility` on `zone`; `zone_viewer`; `AuthzController`
   (`/authz/zone/{slug}`); `TraefikDynamicConfig` emits `forwardAuth` for
   `PRIVATE`; session cookie `Domain=.<BASE_DOMAIN>`; UI to toggle visibility
   and edit the allow-list. All zones default `PUBLIC`.
8. **Compose + Dockerfile + config-properties** finalised (Postgres service,
   shared `ignition-dynamic` volume, `IGN_WORK_DIR`, `IGN_SECRET_KEY`, SMTP env);
   end-to-end run on a live node (bootstrap → zone + admin → private zone →
   invited viewer → deploy).
9. **Docs** (all of the above) + mkdocs nav + `gh-pages`.
