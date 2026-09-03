# ignition-control

The Ignition control plane — one Spring Boot service that replaces the `ign`
bash CLI, the `scripts/*.sh`, and `control/ign-control.py`. See
[`../DESIGN.md`](../DESIGN.md).

Status: **scaffold** (DESIGN.md step 2–3). Working now:

- health at `/actuator/health`
- token auth: platform (`ignition.admin-token`), zone (`state/zones/<slug>/zone-token`),
  CI deploy (`state/zones/<slug>/deploy-token`) → session cookie or `Authorization: Bearer`
- **platform console** (`/`) — live read views of nodes / zones / apps from the
  existing `state/` tree; **register / drain / remove a node** (first write slice)
- **zone console** (`/z`) — ported 1:1 from `ign-control.py`: status + apps,
  Users (create / delete), Repositories (create), per-repo **Release**
  (auto / patch / minor / major, via `ForgejoClient` + `ReleaseService`),
  runner restart + stack status (via `DockerCli`)
- **CI bridge** (`POST /deploy`, `/undeploy`) — stubbed `501` until the compose
  runner lands

## Run

```sh
cd ignition-control
IGN_ADMIN_TOKEN=$(openssl rand -hex 32) \
IGN_STATE_DIR=../state \
BASE_DOMAIN=ignition.example \
./mvnw spring-boot:run
```

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
| `state` | `EnvFile` — the `KEY=value` on-disk format |
| `node` / `zone` / `app` | records + file-tree repositories + services |
| `forgejo` | `ForgejoClient` — per-zone REST wrapper (Jackson 3 / `java.net.http`) |
| `release` | `ReleaseService` — Conventional-Commits bump + `cut` via Forgejo |
| `docker` | `DockerCli` — `docker -H <endpoint> compose …` |
| `web` | `PlatformConsoleController`, `ZoneConsoleController`, `LoginController`, `DeployController` |
| `sweep` | `IdleSweeper` (`@Scheduled`) |
| `resources/compose` | `zone-compose.yml.tmpl`, `app-compose.tmpl` (moved from repo `templates/`) |

## Not yet ported (DESIGN.md steps 4–7)

The CI bridge body (`/deploy` renders `app-compose.tmpl` + `docker compose up`),
`ProvisioningService` (two-phase Forgejo + DinD + runner), `Scheduler`,
`TraefikDynamicConfigService`, zone create / move / destroy, and the
platform-console roster + sweep-now.
