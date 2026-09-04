# Ignition — accounts, teams, and team-owned zones

Status: **proposed** — awaiting approval, then implemented in the steps at the
end.

## Why

Today every human authenticates with a static bearer token:
`IGN_ADMIN_TOKEN` (platform) and a per-zone `zone-token`. There is no concept
of a *person*, no team layer, and no self-service. This change introduces:

- **Email / password accounts** with email-verified activation.
- **Self-signup** that a platform admin must approve.
- **Teams** — a platform admin creates them; each has at least one **team
  admin**.
- **Team-owned zones** — a team admin creates and destroys their team's zones.
- Removal of `IGN_ADMIN_TOKEN` and `zone-token`. Only the per-zone
  **`deploy-token`** survives, for CI.

## Roles

| role | scope | can do |
|---|---|---|
| **platform admin** | global | approve signups; **promote/demote other users as platform admin**; create/rename/delete teams; add/remove any user to/from any team; set/clear team-admin on any membership; everything a team admin can, for any team; register/drain nodes; roster; idle sweep |
| **team admin** | one team | add/remove **approved** users to/from *their* team; **promote/demote any member of that team as team admin**; create/destroy *their* team's zones; set a zone `PUBLIC`/`PRIVATE` and manage its viewer allow-list; full zone-console access for *their* team's zones |
| **member** | one team | zone-console access for *their* team's zones (Forgejo users, repos, releases, apps, runner, status) |
| **CI** (not a person) | one zone | `POST /deploy`, `POST /undeploy` with that zone's `deploy-token` |

**All of these are many-to-many:**

- A user can be a member of **multiple teams**, with a different role
  (`MEMBER` / `TEAM_ADMIN`) per team. Their granted authorities are the **union**
  across every team they belong to, plus `PLATFORM_ADMIN` if the flag is set.
- A team can have **multiple team admins**. At least one `TEAM_ADMIN` must
  remain — the last one can't demote or remove themselves.
- There can be **multiple platform admins**. `is_platform_admin` is a plain flag
  on the user, independent of team membership. At least one must remain — the
  last platform admin can't clear their own flag.

**Promotion.** Any current platform admin can set/clear `is_platform_admin` on
any other user (target must be `ACTIVE`). Any current team admin (or any
platform admin) can set a team member's role to `TEAM_ADMIN` or back to
`MEMBER`. A user with no team membership can sign in but only sees "you're not
in a team yet".

## Account lifecycle

```
                    ┌──────────────────────┐
 signup / invite ─► │ PENDING_VERIFICATION │  (activation email sent)
                    └─────────┬────────────┘
              click link, set password
                    ┌─────────▼────────────┐        admin approves
   self-signup ───► │  PENDING_APPROVAL    │ ─────────────────────┐
                    └──────────────────────┘                      │
   admin invite ────────────────────────────────────────────►┌────▼─────┐
                                                             │  ACTIVE  │
                                                             └────┬─────┘
                                              admin disables      │
                                                             ┌────▼─────┐
                                                             │ DISABLED │
                                                             └──────────┘
```

- **Self-signup** (`/signup`): email → user `PENDING_VERIFICATION`, no roles →
  activation email → set password → `PENDING_APPROVAL` → platform admin
  approves → `ACTIVE`. Still in no team.
- **Admin invite** (from a team page or the users page): email → user
  `PENDING_VERIFICATION` (+ a pending team membership if invited into a team) →
  activation email → set password → `ACTIVE` (no approval step — an admin
  initiated it). If the email already belongs to an `ACTIVE` user, "invite into
  team" just adds the membership.
- **Password reset** (`/forgot`): email → reset link (same token table,
  `purpose = RESET`) → set new password.
- **Disable / re-enable**: platform admin only. A `DISABLED` user cannot log in;
  sessions are invalidated on next request.

## Zone visibility (public / private)

Every zone has a `visibility`. **All zones are `PUBLIC` for now** — this is the
default and, until a team admin changes it, the behaviour is exactly as today:
`git.<slug>.<BASE_DOMAIN>` and every `<app>.apps.<slug>.<BASE_DOMAIN>` are open
to anyone.

Setting a zone to **`PRIVATE`** puts an auth check in front of those published
surfaces (not the zone console — that's always members-only). A request is
allowed if the caller is signed in **and** any of:

- a member (any role) of the zone's owning **team**, or
- a platform admin, or
- their email is on the zone's **viewer allow-list** (`zone_viewer`).

The team admin manages `visibility` and the allow-list from the zone's page in
the team console. Allow-listed people still need an Ignition account (they
`/signup`, get approved or are invited, then their signed-in email is matched
against the list) — there is no anonymous access to a private zone.

**Mechanism.** `ignition-control` writes the per-zone Traefik router snippet
(`state/control/dynamic/<slug>.yml`) already. For a `PRIVATE` zone it adds a
`forwardAuth` middleware on the `git.<slug>` and `*.apps.<slug>` routers,
pointing at `ignition-control`'s own endpoint
`GET /authz/zone/{slug}` — which reads the session cookie and returns
`204` (allow) or `403` (deny, redirecting a browser to `/login`). For a
`PUBLIC` zone the middleware is simply absent. Toggling visibility rewrites the
snippet; Traefik picks it up on the file watch, no restart.

For the session cookie to reach `git.<slug>` and `<app>.apps.<slug>` it is
issued with `Domain=.<BASE_DOMAIN>`, `Secure`, `HttpOnly`, `SameSite=Lax`.

`docker push` / `git` over HTTP still work against a private zone the same way
they do everywhere: `forwardAuth` bypasses requests carrying HTTP Basic / a
git or registry user-agent, and Forgejo authenticates them with a personal
access token (unchanged from the deploy flow).

## First run (bootstrap)

`ignition-control` starts with an empty `app_user` table.

- On startup, if `count(app_user) = 0`, it generates a random **bootstrap
  code** and logs it prominently (`WARN`), every 30 s until claimed:
  `IGNITION SETUP — open https://admin.<BASE_DOMAIN>/setup and enter code: <code>`.
- `GET /setup` is reachable **only while there are zero users**; any other time
  it 404s.
- The form takes the **admin email** + the **bootstrap code**. On submit: create
  the user `PENDING_VERIFICATION` with `is_platform_admin = true`, send the
  activation email, invalidate the code. Clicking the link and setting a
  password makes them `ACTIVE` — no approval.
- After that first user exists, `/setup` is dead and all onboarding is
  `/signup` + approval, or admin invite.

Rationale for the code (rather than "just ask for an email"): `/setup` is
unauthenticated and internet-reachable via the demo forwarder; the code — which
only appears in the operator's container logs — stops a stranger claiming the
instance in the window before you finish.

## Data model (PostgreSQL)

New dependency: **PostgreSQL**. Access via **Spring Data JPA**; schema via
**Flyway** (`V1__auth.sql`). Nodes, zones, apps, and rendered compose files
**stay in the `state/` tree** — compose needs real files and teardown stays
`rm -rf`. Postgres owns *identity* and the *zone→team* link only.

```sql
-- V1__auth.sql  (citext for case-insensitive email)
create extension if not exists citext;

create table app_user (
  id                uuid primary key default gen_random_uuid(),
  email             citext unique not null,
  password_hash     text,                       -- null until activated
  status            text not null,              -- PENDING_VERIFICATION|PENDING_APPROVAL|ACTIVE|DISABLED
  is_platform_admin boolean not null default false,
  created_at        timestamptz not null default now(),
  activated_at      timestamptz
);

create table auth_token (
  token_hash  text primary key,                 -- sha-256 of the random token; raw token only in the email
  user_id     uuid not null references app_user(id) on delete cascade,
  purpose     text not null,                     -- ACTIVATE | RESET
  expires_at  timestamptz not null,
  used_at     timestamptz
);

create table team (
  id          uuid primary key default gen_random_uuid(),
  slug        text unique not null,              -- ^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$
  name        text not null,
  created_at  timestamptz not null default now()
);

create table team_member (
  team_id   uuid not null references team(id) on delete cascade,
  user_id   uuid not null references app_user(id) on delete cascade,
  role      text not null,                       -- MEMBER | TEAM_ADMIN
  added_at  timestamptz not null default now(),
  primary key (team_id, user_id)
);

create table zone_team (
  zone_slug   text primary key,                  -- matches state/zones/<slug>/
  team_id     uuid not null references team(id) on delete restrict,
  visibility  text not null default 'PUBLIC',    -- PUBLIC | PRIVATE
  created_by  uuid references app_user(id),
  created_at  timestamptz not null default now()
);

create table zone_viewer (                        -- extra allowed viewers of a PRIVATE zone
  zone_slug  text not null references zone_team(zone_slug) on delete cascade,
  email      citext not null,
  added_by   uuid references app_user(id),
  added_at   timestamptz not null default now(),
  primary key (zone_slug, email)
);
```

- Activation/reset tokens: 32 random bytes, base64url in the link; only the
  **sha-256** is stored. TTL 24 h (activation) / 1 h (reset). One unused token
  per `(user, purpose)` — issuing a new one deletes the old.
- Passwords: **bcrypt** via Spring Security `DelegatingPasswordEncoder`
  (`{bcrypt}`), cost 12.
- `team_member` is the many-to-many join: a user in three teams has three rows,
  each with its own `role`. No per-user "primary team".
- Deleting a team is blocked while `zone_team` rows reference it (destroy its
  zones first). Removing / demoting the **last** `TEAM_ADMIN` of a team is
  rejected; so is clearing the **last** `is_platform_admin`. Deleting a user is
  blocked while they are the sole team admin of any team.

## SMTP (required)

The service **fails fast on startup** if SMTP isn't configured — no silent
"link in the logs" fallback.

```
IGN_SMTP_HOST      (required)
IGN_SMTP_PORT      (default 587)
IGN_SMTP_USERNAME  (required)
IGN_SMTP_PASSWORD  (required)
IGN_SMTP_FROM      (required, e.g. "Ignition <ignition@theresnolimits.net>")
IGN_SMTP_STARTTLS  (default true)
IGN_PUBLIC_URL     (required, e.g. https://admin.ignition.theresnolimits.net — used to build links)
```

`spring-boot-starter-mail`; a `@ConfigurationProperties` class with
`@Validated @NotBlank` on the required fields so a missing value is a startup
failure with a clear message. Three plain-text templates: activate, approved,
reset.

## Security configuration

`SecurityConfig` is rewritten:

- **Form login** (`/login`) → servlet session. The session cookie is issued
  `Domain=.<BASE_DOMAIN>; Secure; HttpOnly; SameSite=Lax` so it also reaches
  `git.<slug>` and `<app>.apps.<slug>` for private-zone `forwardAuth`.
  `UserDetailsService` loads `app_user` by email; authorities =
  `PLATFORM_ADMIN` (if flag) + `TEAM_ADMIN:<slug>` / `MEMBER:<slug>` per
  membership (union over all the user's teams). `DISABLED`/`PENDING_*` → login
  refused with a specific message.
- **`TokenAuthenticationFilter` shrinks to CI only** — it runs on `POST
  /deploy` and `POST /undeploy`, resolves a `Bearer` token against
  `state/zones/*/deploy-token`, yields `ROLE_DEPLOY` + the slug. Everything
  session-related is removed from it. `TokenResolver` loses its PLATFORM and
  ZONE branches.
- **CSRF re-enabled** for the browser app (the `// SCAFFOLD` TODO). The
  deploy/undeploy endpoints are `csrf.ignoringRequestMatchers(...)` since
  they're bearer-only.
- Authorization:
  - `permitAll`: `/actuator/health/**`, `/login`, `/logout`, `/setup/**`,
    `/signup`, `/activate`, `/forgot`, `/reset`, `/css/**`, `/favicon.ico`
  - `GET /authz/zone/{slug}` → `authenticated()`; the handler itself decides
    204 vs 403 from team membership / allow-list / visibility
  - `POST /deploy`, `/undeploy` → `hasRole("DEPLOY")`
  - `/admin/**` → `hasAuthority("PLATFORM_ADMIN")`
  - `/teams/{slug}/**` and `/z/{zoneSlug}/**` → a custom
    `AuthorizationManager` that checks platform-admin **or** the right
    membership (`TEAM_ADMIN:<slug>` for management routes, `MEMBER:<slug>`+ for
    console routes; for `/z/**` it maps `zoneSlug → team` via `zone_team`)
  - `anyRequest().authenticated()`
- Rate-limit `/login`, `/signup`, `/forgot`, `/setup` (simple in-memory
  per-IP + per-email token bucket) to blunt enumeration / brute force.
- Email enumeration: `/signup` and `/forgot` always show the same "check your
  inbox" result whether or not the address existed.

## New / changed code

```
auth/
  AppUser.java                 @Entity  (+ Status enum)
  AppUserRepository.java       JpaRepository — findByEmail, findByStatus, …
  Team.java  TeamRepository.java
  TeamMember.java (id class)  TeamMemberRepository.java
  ZoneTeam.java  ZoneTeamRepository.java   (carries visibility)
  ZoneViewer.java  ZoneViewerRepository.java
  AuthToken.java  AuthTokenRepository.java
  AccountService.java          signup, invite, activate, approve, disable, resetPassword,
                               bootstrap, setPlatformAdmin (last-admin guard)
  TeamService.java             createTeam, addMember, removeMember, setRole (last-team-admin guard),
                               listForUser
  ZoneOwnershipService.java    createZoneForTeam (→ ProvisioningService.submit + zone_team row),
                               destroyZone (guarded), teamOf(zoneSlug),
                               setVisibility, addViewer, removeViewer,
                               canView(zoneSlug, user) → boolean
  MailService.java             sendActivation / sendApproved / sendReset
  IgnitionUserDetailsService.java
  SmtpProperties.java          @Validated, required fields
security/
  SecurityConfig.java          rewritten (form login + slim deploy filter)
  DeployTokenFilter.java        renamed/trimmed TokenAuthenticationFilter
  ZoneTeamAuthorizationManager.java
  (TokenResolver, IgnitionPrincipal — deploy-only; PLATFORM/ZONE removed)
web/
  SetupController.java         GET/POST /setup           (bootstrap)
  SignupController.java        /signup /activate /forgot /reset
  AccountController.java       /account (change password, see my teams)
  AdminController.java         /admin — pending approvals, users, teams, platform-admin flag
  TeamController.java          /teams/{slug} — members, roles, zones, zone visibility + viewers
  AuthzController.java         GET /authz/zone/{slug} — forwardAuth check for private zones
  ZoneConsoleController.java   auth switched from zone-token principal to membership
  PlatformConsoleController.java, RosterController.java  → /admin/**, platform-admin only
  DeployController.java        unchanged (still deploy-token)
  CurrentUser.java             replaces CurrentPrincipal
config/
  IgnitionProperties.java      drop adminToken; add publicUrl
templates/  setup.html login.html signup.html activate.html forgot.html reset.html
            admin/approvals.html admin/users.html admin/teams.html
            teams/team.html  account.html  (+ update existing console pages)
db/migration/V1__auth.sql
```

`ProvisioningService`, `ZoneService`, `Scheduler`, `ForgejoClient`, the
`docker`/compose layer, templates, sweep — **unchanged** except
`TraefikDynamicConfig.writeZoneRouter(slug)` now takes the zone's visibility and
conditionally emits the `forwardAuth` middleware. Zone creation enters through
`ZoneOwnershipService` (records team + visibility, then calls the existing
`ProvisioningService.submit`). The idle sweeper still calls
`ZoneService.destroy`; it also deletes the `zone_team` / `zone_viewer` rows.

## Compose / deployment changes

`templates/ignition-control-compose.yml`:

- **add** a `postgres:16-alpine` service — named volume `ignition-pgdata`,
  `POSTGRES_DB=ignition`, `POSTGRES_USER=ignition`, `POSTGRES_PASSWORD` from
  env, healthcheck `pg_isready`, on an internal `ignition-internal` network
  (not `traefik-public`).
- `ignition-control` gains `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres/ignition`
  (+ user/password), `depends_on: postgres (service_healthy)`, the `IGN_SMTP_*`
  and `IGN_PUBLIC_URL` env, and joins `ignition-internal` too.
- **remove** `IGN_ADMIN_TOKEN`.

Dockerfile: no base-image change (JDBC driver is a jar). `pom.xml` adds
`spring-boot-starter-data-jpa`, `org.postgresql:postgresql`, `flyway-core`,
`flyway-database-postgresql`, `spring-boot-starter-mail`,
`spring-boot-starter-validation` (already present).

## Docs to update

- `docs/roles.md` — platform admin / team admin / member; the approval flow.
- `docs/architecture.md` — auth model section; the team layer; zone ownership.
- `docs/operating-an-event.md` — first-run `/setup` + bootstrap code; Postgres
  and SMTP prerequisites; creating teams; teams create zones; the roster tool
  becomes "provision zones for a team".
- `README.md`, `CLAUDE.md` — the model, the removed tokens, the new env.
- `demo-1-spitfire.md` — Postgres + SMTP now required; no `IGN_ADMIN_TOKEN`;
  first-run is `/setup` with the code from `docker compose logs`.
- `demo-2-remote-access.md` — provisioning a zone now goes: create a team →
  add yourself as team admin → create the zone.
- `DESIGN.md` — the "Auth model (principals unchanged)" section is superseded;
  link here.
- New `docs/accounts-and-teams.md` in the mkdocs nav — accounts, approval,
  teams, roles, and zone visibility (public/private + allow-list).
- `docs/exposure.md` — reconcile the per-app `visibility` note with the new
  per-zone `PUBLIC`/`PRIVATE` and the `ignition-control` `forwardAuth` check.

## Decisions folded in

- Storage: **PostgreSQL** (identity + zone→team link; infra state stays in `state/`).
- Email: **SMTP required**, fail fast, no log fallback.
- Team admin: **manages own team membership + team-admin role + team zones + zone visibility**.
- Tokens: **`IGN_ADMIN_TOKEN` and `zone-token` removed**; `deploy-token` stays.
- **Many-to-many**: users ↔ teams (role per team), multiple team admins per
  team, multiple platform admins; a last-admin guard on both.
- **Promotion**: platform admin promotes/demotes platform admins; team admin (or
  platform admin) promotes/demotes team admins within a team.
- **Zone visibility**: `PUBLIC` (default, all zones today) or `PRIVATE` (team
  members + platform admins + an email allow-list, enforced by `forwardAuth`).

## Implementation steps

1. **This doc.** ← you are here
2. **DB + entities.** pom deps; `application.yml` datasource + Flyway;
   `V1__auth.sql`; the `auth/` entities + repositories; Postgres in the compose
   file; Testcontainers for the repository tests. Nothing wired to security yet.
3. **Accounts core.** `AccountService` (signup, activate, invite, approve,
   disable, reset), `AuthToken` issue/verify, `SmtpProperties` (fail-fast),
   `MailService` + templates. Unit tests with a stub mail sender.
4. **Security cutover.** Rewrite `SecurityConfig` to form login +
   `IgnitionUserDetailsService`; shrink the token filter to deploy-only; drop
   `adminToken`; re-enable CSRF; `ZoneTeamAuthorizationManager`. Update every
   existing controller/template from principal-kind checks to authorities.
   `/login`, `/signup`, `/activate`, `/forgot`, `/reset` pages.
5. **Bootstrap.** `SetupController` + `setup.html`; startup bootstrap-code
   logger; `/setup` gated on zero users.
6. **Teams.** `Team`/`TeamMember` services; `AdminController`
   (approvals/users/teams) + `TeamController` (members, roles); pages.
7. **Team-owned zones.** `ZoneOwnershipService`; move zone creation/destroy
   behind team-admin auth; `zone_team` recorded and swept; `RosterController`
   becomes per-team; `ZoneConsoleController` auth via membership.
8. **Zone visibility.** `visibility` + `zone_viewer`; `AuthzController`
   (`/authz/zone/{slug}`); `TraefikDynamicConfig` emits `forwardAuth` for
   `PRIVATE`; session cookie `Domain=.<BASE_DOMAIN>`; team-console UI to toggle
   visibility and edit the allow-list. All zones default `PUBLIC`.
9. **Compose + Dockerfile + config-properties** finalised; end-to-end run on a
   live node (bootstrap → team → private zone → invited viewer → deploy).
10. **Docs** (all of the above) + mkdocs nav + `gh-pages`.
