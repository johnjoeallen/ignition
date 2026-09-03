# ignition-control

The Ignition control plane — one Spring Boot service that replaces the `ign`
bash CLI, the `scripts/*.sh`, and `control/ign-control.py`. See
[`../DESIGN.md`](../DESIGN.md).

Status: **in progress** (DESIGN.md through step 5). Working now:

- health at `/actuator/health`
- token auth: platform (`ignition.admin-token`), zone (`state/zones/<slug>/zone-token`),
  CI deploy (`state/zones/<slug>/deploy-token`) → session cookie or `Authorization: Bearer`
- **platform console** (`/`) — live views of nodes / zones / apps from the
  existing `state/` tree; **register / drain / remove a node**; **Provision a
  zone** (`Scheduler` + `ProvisioningService`, off-request with a polled status)
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
| `scheduler` | `Scheduler` — CPU-headroom node placement |
| `provisioning` | `ProvisioningService` — port of `provision-zone.sh` |
| `traefik` | `TraefikDynamicConfig` — the `state/control/dynamic/*` snippets |
| `docker` | `DockerCli` — `docker -H <endpoint> compose …` |
| `templates` | `ComposeTemplate` — explicit `${VAR}` render of the compose files |
| `web` | `PlatformConsoleController`, `ZoneConsoleController`, `LoginController`, `DeployController` |
| `sweep` | `IdleSweeper` (`@Scheduled`) |
| `resources/compose` | `zone-compose.yml.tmpl`, `app-compose.tmpl` (moved from repo `templates/`) |

## Not yet ported (DESIGN.md step 7+)

The platform-console **roster** (bulk create/destroy) and a **sweep-now**
button, and packaging the service as a container with its own
`ignition-control-compose.yml`.

`ProvisioningService` / `Scheduler` (step 5) and zone **move / destroy** + the
real **idle sweep** (step 6) are in place. The parts that need a live Docker
daemon (Forgejo health wait, runner registration, `compose cp`, stack teardown)
are faithful command translations, exercised end-to-end only against a real
node.
