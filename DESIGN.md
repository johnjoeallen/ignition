# Ignition control plane — design for the Java rewrite

Status: **complete** — the Java service is the implementation. The shell CLI +
`scripts/` + `control/ign-control.py` were removed at cutover (step 9); a real
end-to-end provision (Forgejo + DinD + runner, zoneadmin token, teardown) was
verified against a live node. This document is now the design record.

## Why

Today Ignition is a bash dispatcher (`ign`), a set of shell scripts
(`scripts/*.sh`), and a stdlib Python HTTP server (`control/ign-control.py`).
The zone-admin console is already a web UI, but every **platform-admin** write
operation — register a node, create / place / move / destroy a zone, drain a
node, run a roster, sweep idle zones — is CLI-only. Operating an event means
SSHing to the control host and running shell.

Goal: **one application, in Java, deployed as a Docker container, where every
platform-admin and zone-admin operation is done through the web UI.** No shell
scripts to run by hand.

## Language and framework

- **Java 21** (LTS; virtual threads suit the many blocking calls out to
  Docker, Forgejo, and SSH), **Spring Boot 4.0.x**.
  - **Spring MVC + Thymeleaf** for server-rendered console pages — a plain
    multi-page app, no SPA build step, same spirit as `ign-control.py` today.
    Optionally **htmx** for live status and inline form posts (no build step).
  - **Spring Security** for the three principals (below).
  - **Spring Scheduling** (`@Scheduled`) for the idle sweep and periodic
    reconciliation.
  - **`RestClient`** for the Forgejo API.
- Build: **Maven** (`mvnw` wrapper). Output is `target/ignition-control.jar`
  and a **container image** (`ghcr.io/johnjoeallen/ignition-control:<version>`)
  built from a Dockerfile on a **slim JRE base** (`eclipse-temurin:21-jre`)
  that also installs the **`docker` CLI** (incl. the compose plugin) and
  **`openssh-client`** — required for compose operations against local and
  `ssh://` nodes (see "Docker engine access"). Not distroless.
- Group / package: **`net.dublinux.ignition`**.

## The control plane is itself a container

Runs on the **control host** as the `ignition-control` service, next to that
host's core stack.

- **Docker access.** Mounts `/var/run/docker.sock` to manage the local node.
  For remote nodes it uses the node record's endpoint — `unix://…`,
  `ssh://user@host`, or `tcp://host:2376` (+ mounted TLS client certs) — via a
  mounted SSH key where needed.
- **Networking.** Joins `traefik-public`; Traefik routes to it by Docker
  labels — no more `host.docker.internal` loopback hop. The platform router
  `admin.<BASE_DOMAIN>` is a label on this container. Per-zone
  `admin.<slug>.<BASE_DOMAIN>` routers still need their own `tls.domains`
  entries, so the app keeps writing `state/control/dynamic/<slug>.yml` for
  Traefik's file provider (same as today — the writer is now the Java app).
- **State volume.** Mounts `state/` (see "State").
- **Secrets.** `IGN_ADMIN_TOKEN` from env / a Docker secret; per-zone tokens
  live in `state/`.
- **Config.** `application.yml` + env overrides: `BASE_DOMAIN`, `ACME_*`, the
  per-zone quota defaults, the idle TTL, Docker / SSH settings.
- **Self-exclusion.** The container that manages containers must never manage
  or restart itself — exclude by name / label everywhere it enumerates
  containers. Control-plane updates are deliberate (not Watchtower-driven)
  unless we explicitly opt in.

## Package layout

```
ignition-control/
  pom.xml   mvnw   .mvn/
  Dockerfile                       # eclipse-temurin:21-jre + docker CLI + openssh-client
  src/main/java/net/dublinux/ignition/
    IgnitionControlApplication.java
    config/        SecurityConfig, DockerConfig, SchedulingConfig, IgnitionProperties
    security/      token auth (platform / zone / deploy) -> IgnitionPrincipal
    node/          Node, NodeService, NodeRepository
    zone/          Zone, ZoneService, ZoneRepository
    provisioning/  ProvisioningService   — two-phase Forgejo+DinD+runner,
                   runner registration + UUID derivation, `compose cp` push
    scheduler/     Scheduler            — CPU-headroom placement, capacity accounting
    app/           DeployedApp, AppService, the CI deploy/undeploy bridge
    release/       ReleaseService       — Conventional-Commits bump, tag via Forgejo
    forgejo/       ForgejoClient        — per-zone REST wrapper
    docker/        DockerEngine (transport per node), ComposeRunner
    traefik/       TraefikDynamicConfigService  — writes state/control/dynamic/*
    templates/     compose rendering (explicit var lists, envsubst-style)
    sweep/         IdleSweeper (@Scheduled)
    web/           PlatformConsoleController, ZoneConsoleController, DeployController
  src/main/resources/
    templates/*.html                 # Thymeleaf
    compose/zone-compose.yml.tmpl     # moved from repo templates/
    compose/app-compose.tmpl
    application.yml
```

## What each shell script becomes

| today | in the service |
|---|---|
| `ign node add/list/show/drain/undrain/rm` | `NodeService` + **Platform → Nodes** (register form; drain / undrain / remove) |
| `ign zone create` → `provision-zone.sh` | `ZoneService.create()` → `ProvisioningService.provision()` + **Platform → Zones → New zone**; progress streamed |
| `scheduler.sh` `pick_node()` | `Scheduler.place(cpu, memGb, label)` |
| `ign zone move` | `ZoneService.move()` (keep-state teardown on the old node, re-provision on the new) |
| `ign zone destroy` → `teardown-zone.sh` | `ZoneService.destroy()` — compose `down -v` for the zone + every app, remove state + dynamic snippet |
| `sweep-idle.sh` (cron) | `IdleSweeper` `@Scheduled`; **Platform** shows idle zones + "sweep now" |
| `ign app list/show/rm` | `AppService` + **Platform → Apps** |
| `ign control` | *is* the application |
| `control/ign-control.py` zone console | `ZoneConsoleController` + Thymeleaf — same surfaces |
| `runner-config.yml` written by `provision-zone.sh` | `ProvisioningService` builds it in code, `compose cp`s it in |
| `forgejo_uuid()` in `lib.sh` | `ProvisioningService.deriveRunnerUuid()` |
| `state/control/dynamic/*.yml` writer | `TraefikDynamicConfigService` |

## The UI

Two authenticated consoles served by the one app.

**Platform console** — `admin.<BASE_DOMAIN>`, platform token:

- **Nodes** — table (endpoint, cpu/mem, allocated vs capacity, state); *Register
  node* form; *Drain / Undrain / Remove*.
- **Zones** — table (zone, node, footprint, app count, links); *New zone* form
  (slug, optional node pin / label) → runs provisioning and streams progress,
  then shows the zone-admin sign-in link and tokens; per-zone *Move / Destroy /
  Open console*.
- **Apps** — every deployed app across all zones; *Stop*.
- **Roster** — paste / upload a list of zone slugs → bulk create or bulk
  destroy (closes the "no roster loop" gap).
- **Idle** — zones past the TTL; *Sweep now*.
- **Health** — `BASE_DOMAIN`, quota defaults, ACME status, per-node Docker
  connectivity, control-plane version.

**Zone console** — `admin.<slug>.<BASE_DOMAIN>`, zone token: unchanged from
today — Users, Repositories (create + per-repo **Release** with
auto / patch / minor / major), Apps (status / remove), Restart runner, status
card.

**CI bridge** — bearer = deploy token: `POST /deploy`, `POST /undeploy`. The
JSON contract is **unchanged**, so `examples/deploy.yml` does not change.

No CLI is part of the management path, and none is kept for scripting — the
REST endpoints are the API.

## Docker engine access

`DockerEngine` picks a transport from the node record. For all compose
operations (`up`, `down -v`, `cp`, `exec`, `ps`) the service **invokes the
`docker` CLI** via `ProcessBuilder` with `-H <endpoint>` (`unix://`,
`ssh://user@host`, or `tcp://host:2376` + `DOCKER_CERT_PATH`) — the same
commands the shell scripts run today, so the two-phase provision and the
SSH-safe `compose cp` trick carry over unchanged. This is why the image is a
JRE base with the `docker` CLI + `openssh-client` on it, not distroless. A
structured client (docker-java, over the local socket) can be added later for
cheap status / inspect calls, but is not required for v1.

## State

Keep the current **`state/` file tree**, wrapped in repository interfaces
(`NodeRepository`, `ZoneRepository`, `AppRepository`). Same layout as today:
`state/nodes/<name>.env`, `state/zones/<slug>/…`, `state/control/dynamic/…`,
rendered compose and `runner-config.yml` as real files (compose and `compose
cp` need real files anyway). Add an append-only `state/audit.log`.

Rationale: zero migration, human-auditable, `git`-diffable, teardown stays
`rm -rf`, and the port stays reviewable. Move metadata to SQLite/H2 + Flyway
only if the UI later needs richer history than the audit log gives.

## Auth model (principals unchanged)

- **Platform admin** — `IGN_ADMIN_TOKEN`. Form login at `admin.<BASE_DOMAIN>` →
  session cookie; or `Authorization: Bearer`.
- **Zone admin** — per-zone `zone-token` (in state). Form login at
  `admin.<slug>.<BASE_DOMAIN>` → session scoped to that zone.
- **CI** — per-zone `deploy-token`, bearer only, only `POST /deploy|/undeploy`.
- A Spring Security filter resolves the token → `IgnitionPrincipal(kind, slug)`;
  URL / method rules enforce the split. The `zoneadmin` Forgejo account + API
  token stay a server-held service credential.

## Templates

`zone-compose.yml.tmpl` and `app-compose.tmpl` move into
`src/main/resources/compose/`. Rendering stays **explicit-variable
substitution** — a small `${VAR}` replacer given the exact allowed key set
(mirrors the `*_TMPL_VARS` discipline) — not a template engine, so a stray `$x`
in a compose file is left alone. `runner-config.yml` is built in code (it has
conditionals — the reason it isn't a template today).

## Packaging and the core stack

- New `templates/ignition-control-compose.yml` (control host only): the
  `ignition-control` image, `/var/run/docker.sock` mount, SSH-key / TLS-cert
  mounts, the `state` volume, `traefik-public`, and Traefik labels for
  `admin.<BASE_DOMAIN>` plus the dynamic-config volume for per-zone
  `admin.<slug>` routers.
- `traefik-core-compose.yml` is unchanged for worker nodes. The control host
  runs core **and** control.
- `ghcr.io/johnjoeallen/ignition-control:<version>` published to GHCR.
- Health via `/actuator/health` + a Docker healthcheck.
- At cutover, `ign`, `scripts/`, and `control/ign-control.py` are removed.

## Migration / cutover

1. ~~Land this doc.~~ **done**
2. ~~Scaffold `ignition-control/`~~ **done** — Maven, Spring Boot 4, token auth
   (platform / zone / deploy), `/actuator/health`, both console shells, the CI
   `/deploy` bridge stubbed `501`, `EnvFile` + file-tree repositories for
   nodes / zones / apps, `IdleSweeper` (`@Scheduled`, reports only).
3. **done** — read paths live (platform console renders nodes / zones / apps
   from `state/`). Node register / drain / remove works. The **zone console is
   ported 1:1** from `ign-control.py`: `ForgejoClient` (per-zone REST via the
   `zoneadmin` token), `ReleaseService` (`latestSemver` / `bump` /
   `classifyBump` / `cut`, unit-tested), Users create/delete, Repositories
   create + per-repo **Release** (auto / patch / minor / major), runner restart
   and stack status via a `DockerCli` (`docker -H <endpoint> compose …`).
4. **done** — the CI bridge. `ComposeTemplate` (explicit `${VAR}` render,
   unit-tested), `AppService.deploy` / `undeploy` (name + registry checks,
   render `app-compose.tmpl` to `state/zones/<slug>/apps/<name>-compose.yml`,
   `docker compose -p app-<slug>-<name> up -d --pull always` on the zone's
   node, write the app record, bump `last-activity`). `DeployController` maps
   bad input to 400 and a failed compose to 502; contract unchanged.
5. **mostly done** — `Scheduler` (CPU-headroom, unit-tested), `ProvisioningService`
   (full port of `provision-zone.sh`: footprint + placement, `zone.env`,
   `TraefikDynamicConfig` snippets, render `zone-compose.yml.tmpl`, two-phase
   apply, `forgejoUuid` derivation unit-tested, runner-config, zoneadmin +
   tokens) running off-request with a status the console polls; platform
   console gains a **Provision a zone** form. The container-orchestration steps
   are faithful command translations — full end-to-end needs a real daemon.
6. **done** — `ZoneService.destroy` (port of `teardown-zone.sh`: every app's
   compose down + optional state removal + router-snippet removal + zone stack
   down / stray-container sweep) and `prepareMove` (teardown-keep-state, drop
   per-node artefacts, repoint `NODE`, caller re-provisions). `IdleSweeper`
   now actually reclaims (`ignition.sweep.dry-run` to only report). Platform
   console: per-zone move / destroy, per-app stop.
7. **done** — every platform write op is in the console: register / drain /
   remove a node, provision / move / destroy a zone, stop an app, and a
   `/roster` page (bulk apply / teardown a slug list) + "sweep idle zones now".
   Provisioning runs on a small pool (`ignition.provisioning.concurrency`, 3)
   so a roster fans out.
8. **done** — multi-stage `Dockerfile` (maven build -> JRE + docker CLI +
   compose plugin + ssh client). `templates/ignition-control-compose.yml`:
   control-host only, mounts `/var/run/docker.sock` + `../state` + ssh keys,
   joins `traefik-public`, sets `IGNITION_CONTROL_PLANE_URL=http://ignition-control:8790`
   so the file-provider routers reach it. `TraefikDynamicConfig` writes
   `_platform.yml` on startup. Verified: image builds, container is healthy,
   the in-container `docker` talks to the host socket.
9. **done** — a real `provision` was run against a live `local` node: Forgejo +
   DinD + runner up and healthy, the runner registered, the `zoneadmin`
   account + a real Forgejo API token minted; the zone console then created a
   repo through the proxied API; `destroy` removed all three containers, all
   volumes, and the state tree — 0 errors. Then the cutover: `ign`,
   `scripts/*.sh`, `control/ign-control.py` and the repo-root `*.tmpl`
   templates deleted; README / CLAUDE.md / all `docs/*.md` rewritten to the
   console. `examples/deploy.yml` untouched — the contract is preserved.

## Decisions

- **Language / framework:** Java 21 + Spring Boot 4.0.x, Maven.
- **Package:** `net.dublinux.ignition`.
- **Image:** `eclipse-temurin:21-jre` + `docker` CLI (with compose plugin) +
  `openssh-client`. Not distroless.
- **State:** the `state/` file tree for v1 (repository interfaces + an audit
  log); revisit SQLite only if the UI outgrows it.
- **CLI:** none. The old `ign` and `scripts/` are removed at cutover; no
  read-only wrapper is kept — "no CLI magic" is the whole point.
- **Image:** `ghcr.io/johnjoeallen/ignition-control:<version>`.
- **Compose:** ships as its own `templates/ignition-control-compose.yml`, run
  on the control host only. `traefik-core-compose.yml` is unchanged and runs on
  every node including the control host.
