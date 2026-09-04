# CLAUDE.md

Context for Claude Code when working in this repo. Read this before making
changes — several design decisions here look "wrong" in isolation but were
made deliberately to work around real constraints. See the "Decisions and
why" section before refactoring any of them.

## What this is

Infrastructure for a private, short-lived, per-team hackathon environment.
Many teams (target ~80), a small pool of hosts ("nodes"). Each team gets a
fully isolated stack (a "zone").

The whole thing is **one Java (Spring Boot 4) service, `ignition-control`,
deployed as a container** on the control host. Every platform-admin and
zone-admin operation is in its web UI — there is no CLI. Design and the
port history are in **[DESIGN.md](DESIGN.md)**; the module is
[`ignition-control/`](ignition-control/).

`BASE_DOMAIN` is the apex where Ignition is hosted (e.g. `ignition.example`).
Each zone owns the whole `<slug>.<BASE_DOMAIN>` subtree; the platform admin
sits on the apex:

| host | what |
|---|---|
| `admin.<BASE_DOMAIN>` | the platform console (`ignition-control`) |
| `git.<slug>.<BASE_DOMAIN>` | that zone's Forgejo — git, PRs, Actions, registry |
| `admin.<slug>.<BASE_DOMAIN>` | that zone's console (same service, zone-scoped) |
| `<app>.apps.<slug>.<BASE_DOMAIN>` | a deployed app (name unique within the zone) |

`ignition.example` in the docs is a placeholder; `BASE_DOMAIN` is whatever apex
the deploying org controls, provided its DNS can serve names two labels deep and
it can obtain `*.<slug>.<apex>` wildcard certs.

Per zone:

- **git hosting + PRs/issues + CI + container registry** — one Forgejo
  instance at `git.<slug>.<BASE_DOMAIN>` (no separate GitLab/Woodpecker/registry:2)
- **isolated build sandbox** — a `docker:dind` sidecar, so one zone's CI can
  never see another zone's containers/images/network
- **any number of live apps** — CI builds + pushes an image on a release, then
  POSTs the control plane, which runs it at `<app>.apps.<slug>.<BASE_DOMAIN>`

Two admin roles:

- **Platform admin** — registers **nodes**, provisions/moves/destroys **zones**,
  runs the roster, sees everything. Works in the **platform console** at
  `admin.<BASE_DOMAIN>` (bearer = `IGN_ADMIN_TOKEN`).
- **Zone admin** — one per zone (the team lead). Manages *their* zone only —
  add/remove users, create repos, **cut releases** (the console's *Release*
  button derives the bump from the commits since the last release and tags the
  next `vX.Y.Z` on `main` — a dropdown overrides), restart the runner, stop
  apps, watch status — through the **zone console** at
  `admin.<slug>.<BASE_DOMAIN>`, which proxies that zone's own Forgejo admin API.

## Repo layout

```
ignition-control/                # the control plane — one Spring Boot service
  pom.xml  mvnw  Dockerfile       # Java 21, Maven; multi-stage image (JRE + docker CLI)
  src/main/java/net/dublinux/ignition/
    config/        IgnitionProperties, SecurityConfig
    security/      token -> IgnitionPrincipal (platform / zone / deploy), auth filter
    state/         EnvFile — the KEY=value on-disk format
    node/ zone/ app/   records + file-tree repositories + services
    scheduler/     Scheduler — CPU-headroom node placement
    provisioning/  ProvisioningService — the two-phase Forgejo+DinD+runner apply
    forgejo/       ForgejoClient — per-zone REST wrapper (zoneadmin token)
    release/       ReleaseService — Conventional-Commits bump + cut via Forgejo
    docker/        DockerCli — docker -H <endpoint> compose ...
    traefik/       TraefikDynamicConfig — writes state/control/dynamic/*
    templates/     ComposeTemplate — explicit ${VAR} render
    sweep/         IdleSweeper (@Scheduled)
    web/           Platform / Zone / Roster / Login / Deploy controllers + Thymeleaf
  src/main/resources/compose/     zone-compose.yml.tmpl, app-compose.tmpl
templates/
  ignition-control-compose.yml   # run the control plane on the control host
  traefik-core-compose.yml       # per-node core: Traefik + Watchtower
examples/deploy.yml              # sample workflow to seed into a zone's repo
state/
  nodes/<name>.env               # node registry (DOCKER_HOST, CPUS, MEM_GB, LABELS, STATE)
  zones/<slug>/                   # per-zone: zone.env, docker-compose.yml, runner-secret,
                                  #   zone-admin.txt, zone-token, deploy-token, last-activity
  zones/<slug>/apps/<name>.env    # app registry (APP_NAME, ZONE, NODE, IMAGE, PORT, DEPLOY_ID)
  zones/<slug>/apps/<name>-compose.yml   # rendered per-app compose
  control/dynamic/*.yml           # Traefik file-provider snippets: admin.<BASE_DOMAIN> +
                                  #   admin.<slug>.<BASE_DOMAIN> -> ignition-control
```

`state/` is generated, never source; `nodes/`, `zones/`, `control/` are gitignored.

## Decisions and why (don't relitigate these without reading first)

**"Zone" = one team's isolated stack, assigned 1:1 to a node.** Every per-zone
Docker resource is prefixed `zone-<slug>` (containers, networks, volumes,
compose project) so `docker compose -p zone-<slug> down -v` is a complete,
safe teardown. A node is a host that runs zone stacks; the control host runs
`ignition-control`, which reaches each node's Docker daemon over the `docker`
CLI (`-H` local socket / `ssh://` / `tcp://`+TLS).

**One subtree per zone — `<slug>.<BASE_DOMAIN>` — not a flat `*.<BASE_DOMAIN>`.**
Docker hits `/v2/...` at the domain *root* for registry operations and ignores
path prefixes, so a forge at `<slug>.<domain>/git` would break `docker push`
against its own registry. Each Forgejo therefore owns a whole origin,
`git.<slug>.<BASE_DOMAIN>`. Everything else for the zone hangs off the same
subtree: `admin.<slug>.<BASE_DOMAIN>` (the zone console) and
`<app>.apps.<slug>.<BASE_DOMAIN>` (one per deployed app). The platform admin is
one host on the apex, `admin.<BASE_DOMAIN>`.

TLS scope follows from this: a `*.<BASE_DOMAIN>` wildcard is single-label and
misses anything two labels deep. So the control host's Traefik holds the apex
cert (`<BASE_DOMAIN>` + `*.<BASE_DOMAIN>`, which covers `admin.<BASE_DOMAIN>`),
and **each zone's own Forgejo router** additionally requests
`*.<slug>.<BASE_DOMAIN>` + `*.apps.<slug>.<BASE_DOMAIN>` — covering that zone's
git, admin, and every app with no per-name request. `admin.<slug>.<BASE_DOMAIN>`
is served by `ignition-control` behind the control host's Traefik via a
file-provider snippet (`state/control/dynamic/<slug>.yml`, written by
`TraefikDynamicConfig` at provision time) that carries its own
`*.<slug>.<BASE_DOMAIN>` cert request. DNS routes per name: `git.<slug>` /
`*.apps.<slug>` A-records point at the node running that zone, `admin.<slug>`
and `admin.<BASE_DOMAIN>` at the control host (single-host: wildcard
`*.<slug>.<BASE_DOMAIN>`). Creating those records is a known gap.

**App names are unique within a zone, not globally.** An app is
`state/zones/<slug>/apps/<name>.env` recording its zone, node, and image.
`ignition-control` only runs an image from the requesting zone's own registry
(`git.<slug>.<domain>/…`). Each app is its own compose project
`app-<slug>-<name>` on the zone's node.

**Apps are deployed from the control host onto the zone's node, not from inside
the zone's DinD sandbox.** A container built and run inside a nested Docker
engine is in that engine's private netns — Traefik can't route to it. So CI
builds + pushes an image, then `POST /deploy` (`{app, image, port}`, per-zone
deploy token); `AppService` renders `app-compose.tmpl` and runs it on the
zone's node's real daemon, on `traefik-public`. `POST /undeploy` (or **Stop**
in the console) tears one down.

**Deploys come only from a release — never a plain push to `main`.** The CI
workflow (`examples/deploy.yml`) triggers on a git tag only. Tags are created
by the zone console's **Release** button: `ReleaseService` diffs the last tag
against `main`, classifies the bump from those commit messages (Conventional
Commits: `feat!:`/`BREAKING CHANGE:` → major, `feat:` → minor, else patch; a
`bump=` override skips this), and creates the next `vMAJOR.MINOR.PATCH` tag on
`main` (first release `v0.1.0` or `v1.0.0`). The zone admin never types a
version. Each run pushes `:<sha>` (immutable) + `:<tag>` and `POST /deploy`s
`:<tag>`, rolling the app forward immediately. Independently, a **per-node
Watchtower** (in `traefik-core-compose.yml`, `--label-enable`, 60s poll) pulls a
new digest for any container labelled
`com.centurylinklabs.watchtower.enable=true` — which `app-compose.tmpl` sets on
every app the control plane deploys, so teams get auto-reload without touching
their repo — and never touches Traefik/Forgejo/DinD/runners.

**One central control plane, not an agent per node.** `ignition-control`
orchestrates across nodes: it holds `IGN_ADMIN_TOKEN` (platform), each zone's
`zone-token` (zone admin) and `deploy-token` (CI), reaches every node's Docker
daemon over the `docker` CLI, and reaches every zone's Forgejo over the public
`git.<slug>.<domain>` API with the admin token minted at provisioning. Zone
admins never get node or Docker access — every action is a proxied Forgejo API
call or a `docker compose` command scoped to a `zone-<slug>` /
`app-<slug>-<name>` project.

**Node placement is CPU-headroom first.** `Scheduler` picks the active node with
the most free CPU (capacity minus the sum of assigned zones' quota limits) that
can fit the zone and carries any required label. Quotas are limits, not
reservations, so nodes oversubscribe — but a zone whose limits alone exceed a
node is never placed there. Pinning a node in the provision form overrides it.

**Runner registration is a two-phase apply, and the runner config is pushed
in with `docker compose cp`.** Forgejo needs its DB (first-run init) before
`forgejo forgejo-cli actions register --secret <40 hex>` can run, so
`ProvisioningService` brings up `forgejo`+`dind`, waits for health, registers,
then brings up the `runner` and `docker compose cp`s the generated
`runner-config.yml` into its `/data` volume. `cp` (not a bind mount) so it
works whether the node is local or reached over SSH. The runner's UUID is
derived from the secret locally (`ProvisioningService.forgejoUuid`) — first 16
chars, as ASCII bytes, hex, `8-4-4-4-12` — so we don't parse the register
command's stdout.

## Conventions

- Compose templates are rendered by `ComposeTemplate` — explicit `${VAR}`
  substitution over a fixed allow-list (`APP_VARS` / `ZONE_VARS`); anything not
  in the map (a stray `$x`, an unknown `${VAR}`) is left verbatim. Never a
  template engine. `runner-config.yml` has conditionals, so it's built in code,
  not templated.
- Per-zone resource limits are `ignition.quotas.*` in `application.yml`
  (`cpu-forgejo`, `mem-dind`, …), overridable by env.
- The control plane is stdlib + Spring — no ORM, no SPA build step. State is a
  file tree under `state/` (repository interfaces wrap it); move to SQLite only
  if the UI outgrows it. Compose ops shell out to the `docker` CLI.
- `state/` is generated — never hand-edit; re-provision from the console.

## Known gaps (see README "rough edges" for the full list)

- **Nothing creates the `git.<slug>` / `<app>.apps.<slug>` / `admin.<slug>` DNS
  records.** Provisioning and deploy assume they resolve (wildcard the subtree
  for a single node; per-record across nodes). Wiring it to the DNS-provider API
  Traefik already uses is the top task.
- **`traefik-public` is one flat network.** The app containers and the Forgejo
  instances on a node can reach each other by IP; the untrusted code is the app
  container. A Traefik-per-zone network or an L3 policy would close it.
- **`ignition-control` holds every token** and drives every node's Docker
  daemon — it needs a locked-down deployment (its own TLS front, restricted
  socket access).
- **No repo seeding.** The starter repo, `deploy.yml`, and repo vars/secrets
  are still set up by hand per zone.
- **No services catalogue.** A team that needs Postgres, a mock of an internal
  API, or a keyed proxy to an external one stands it up by hand. See task 3.
- **`move` rebuilds the zone empty** — the Forgejo data volume doesn't follow.
- **Only the `public` exposure profile exists** — direct inbound + DNS-01
  wildcard certs, Traefik on every node with a DNS record per zone. Reverse
  tunnels, corp-internal CA, plain-HTTP fallback, SSO gating, and the
  **single-ingress** model (all inbound terminates at the control-plane edge,
  which reverse-proxies by `Host` to the node — one wildcard DNS record, all
  certs central, worker nodes take no inbound) are designed (`docs/exposure.md`)
  but not built. See task 4.

## Likely next tasks

1. Provision and `/deploy` create the `git.<slug>` / `<app>.apps.<slug>` DNS
   records via the DNS-provider API, and provision seeds the starter repo
   (`deploy.yml` + repo vars/secrets) through the zone's Forgejo API — so a
   zone, and then an app, is usable end to end from one action.
2. Zone-level quota requests (zone admin asks, platform admin approves) and a
   `move` that carries the Forgejo data volume.
3. **A per-zone services catalogue** — a "Services" section in the console. An
   app's *own* infrastructure (Postgres, Redis, a cache) lives in that app's
   Dockerfile, versioned and owned by the team. The catalogue is for
   **org-standard shared services** a team would otherwise fake or beg for: a
   card-art lookup, a rewards/points engine, a payments sandbox, an internal
   data API, an LLM gateway. One click adds one to the zone. Two kinds:
   - **Standard mocks** — a canned, org-blessed implementation of an internal
     service. Rendered from `catalogue/<name>.compose.tmpl` (same
     `ComposeTemplate` discipline) onto a per-zone `svc-<slug>` network the
     zone's apps also join, with **no Traefik router** — reachable only by that
     zone's apps, by DNS name.
   - **Credentialed proxies** — a small proxy in front of the *real* internal or
     external service, injecting an org-held key `ignition-control` stores (same
     model as `deploy-token` / `zone-admin.txt`). The team gets an endpoint on
     `svc-<slug>`; the real key never reaches their repo or laptop, and the
     platform can meter / rotate / revoke it per zone.
   Compose project `svc-<slug>-<name>`, torn down with the zone. Catalogue
   entries: compose template + a manifest (ports, env, secrets it needs, mock
   vs proxy).
4. **Exposure profiles** (`docs/exposure.md`). All self-hosted — no third-party
   tunnel or mesh services. **Single ingress**: all inbound terminates at the
   control-plane box — one Traefik / SSO edge owns `:80/:443` for
   `*.<BASE_DOMAIN>`, terminates TLS, and reverse-proxies by `Host` to the node
   running the zone (which keeps an internal-only Traefik for the final
   `Host` → container hop). So DNS is one wildcard → the control plane, all
   certs are issued there (`ignition-control` writes the router + cert config
   per zone like it already writes `state/control/dynamic/<slug>.yml`), and
   worker nodes take no inbound. The internal Ignition network
   (`traefik-public`, `zone-<slug>` nets, DinD) is always isolated with no route
   out. The control-plane box is multi-homed; `IGN_EXPOSE_ADDR` is which
   interface the edge binds (corp-DMZ IP / public IP / LAN IP). Topologies A–E
   in the doc. A cluster-level
   `ignition.exposure.profile` — `public` (today) / `public-http01` / `relay` /
   `internal-ca` / `http-only` — plus an `sso` layer (mandatory on a DMZ
   interface). It decides the Traefik entrypoint (`websecure` vs `web`), the
   cert resolver (`le-dns` / `le-http` / `internal` / none — the ACME CA is
   configurable via `ACME_CA_SERVER`, so a self-hosted `step-ca` works), and
   whether a `forward-auth` middleware is attached.
   `traefik-core-compose.yml` gains an optional reverse-tunnel client
   (`rathole` / `frp` / `ssh -R` to a relay host the operator runs) and an
   optional `oauth2-proxy` / Authelia service, enabled by the profile.
   `ComposeTemplate` renders the app / zone Traefik labels from
   `profile × visibility`, where `visibility` (`public` / `corp` / `private`) is
   a per-app field in the `/deploy` payload and the zone console.
   `ignition-control` records the effective scheme + host so the console and
   `/info` show the right URL.

   **Auth model** (settled): one `forward-auth` gateway in front of **all
   browser traffic** — platform console, zone console, Forgejo web UI, apps —
   redirecting to the corp IdP; **zero software on the dev machine** (a browser
   + corp login). "Managed devices only" is an IdP Conditional Access policy,
   not an Ignition feature. `git push` / `docker push` can't do OIDC, so the
   gateway **bypasses** HTTP-Basic / git-user-agent requests and lets Forgejo
   authenticate them with a personal access token the dev mints after their
   first SSO'd login (like a GitHub PAT, over HTTPS). Forgejo SSH stays
   disabled. Contractors get an IdP guest account, not a carve-out.
