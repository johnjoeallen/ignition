# ignition-control

The Ignition control plane — one Spring Boot service that replaces the `ign`
bash CLI, the `scripts/*.sh`, and `control/ign-control.py`. See
[`../DESIGN.md`](../DESIGN.md).

Status: **in progress**. The control-plane feature set is complete; auth and
persistence are being reworked per [`../AUTH-DESIGN.md`](../AUTH-DESIGN.md).

**Persistence: PostgreSQL** (AUTH-DESIGN step 2, done). Nodes, zones, apps,
provisioning status, and encrypted zone secrets are rows; Flyway owns the
schema (`resources/db/migration`). The `state/` file tree is gone — the compose
files, runner config, and Traefik snippets external tools read are re-rendered
from the DB into an ephemeral `IGN_WORK_DIR` (`RenderService`, and
`TraefikDynamicConfig` rebuilds `control/dynamic` on startup). `zone_secret`
values are AES-GCM with `IGN_SECRET_KEY` (`SecretCipher`).

Working now:

- health at `/actuator/health`
- token auth: platform (`ignition.admin-token`), zone / CI deploy
  (`zone_secret` rows) → session cookie or `Authorization: Bearer`
  *(replaced by accounts in AUTH-DESIGN steps 3–7)*
- **platform console** (`/`) — live views of nodes / zones / apps;
  **register / drain / remove a node**; **provision / move / destroy a zone**,
  **stop an app**, a **roster** page + **sweep idle zones now**
- **zone console** (`/z`) — ported 1:1 from `ign-control.py`: status + apps,
  Users (create / delete), Repositories (create), per-repo **Release**
  (auto / patch / minor / major, via `ForgejoClient` + `ReleaseService`),
  runner restart + stack status (via `DockerCli`)
- **CI bridge** (`POST /deploy {app,image,port}`, `POST /undeploy {app}`) —
  renders `app-compose.tmpl` and applies `docker compose -p app-<slug>-<name>`
  on the zone's node; name + registry checks; 400 / 502 on failure

## Run

```sh
cd ignition-control
docker run -d --name ignition-pg -e POSTGRES_DB=ignition -e POSTGRES_USER=ignition \
  -e POSTGRES_PASSWORD=ignition -p 5432:5432 postgres:16-alpine

IGN_ADMIN_TOKEN=$(openssl rand -hex 32) \
IGN_SECRET_KEY=$(head -c32 /dev/urandom | base64) \
IGN_SMTP_HOST=localhost IGN_SMTP_USERNAME=x IGN_SMTP_PASSWORD=x \
IGN_SMTP_FROM='Ignition <ignition@example.com>' \
BASE_DOMAIN=ignition.example \
./mvnw spring-boot:run
```

SMTP is **required** (the service won't start without it) — a
[`maildev`](https://hub.docker.com/r/maildev/maildev) container works for local dev.

Then `http://localhost:8790/login` with the admin token.

## Build

```sh
./mvnw package                 # -> target/ignition-control.jar
docker build -t ghcr.io/johnjoeallen/ignition-control:dev .
```

## Layout

| package | role |
|---|---|
| `config` | `IgnitionProperties`, `SecurityConfig` |
| `security` | token → `IgnitionPrincipal`, the auth filter |
| `auth` | `AppUser` / `AuthToken` / `ZoneMember` / `ZoneViewer` entities, `AccountService`, `MailService`, `SmtpProperties`, `PasswordConfig` |
| `node` / `zone` / `app` | JPA entities + repositories + services; `zone.SecretCipher` |
| `db/migration` | Flyway schema (`V1__infra.sql`, `V2__identity.sql`) |
| `forgejo` | `ForgejoClient` — per-zone REST wrapper (Jackson 3 / `java.net.http`) |
| `release` | `ReleaseService` — Conventional-Commits bump + `cut` via Forgejo |
| `scheduler` | `Scheduler` — CPU-headroom node placement |
| `provisioning` | `ProvisioningService` — port of `provision-zone.sh` |
| `traefik` | `TraefikDynamicConfig` — the `control/dynamic/*` snippets, rebuilt from the DB on startup |
| `docker` | `DockerCli` — `docker -H <endpoint> compose …` |
| `templates` | `ComposeTemplate` (explicit `${VAR}` render) + `RenderService` (DB rows → work-dir files) |
| `web` | `PlatformConsoleController`, `ZoneConsoleController`, `RosterController`, `LoginController`, `DeployController` |
| `sweep` | `IdleSweeper` (`@Scheduled`) |
| `resources/compose` | `zone-compose.yml.tmpl`, `app-compose.tmpl` (moved from repo `templates/`) |

## Build the image

```sh
docker build -t ghcr.io/johnjoeallen/ignition-control:dev .
```

Multi-stage (maven build -> JRE + docker CLI + compose plugin + ssh client).
Run it with `templates/ignition-control-compose.yml` on the control host.

## Not yet done (DESIGN.md step 9)

One real end-to-end provision against a live node, then the cutover: retire
`ign` + `scripts/` + `ign-control.py` and rewrite the docs.

Every operator action is now in the console (nodes, zones, apps, roster, sweep).
The parts that need a live Docker daemon (Forgejo health wait, runner
registration, `compose cp`, stack teardown) are faithful command translations,
exercised end-to-end only against a real node.
