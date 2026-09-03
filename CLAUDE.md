# CLAUDE.md

Context for Claude Code when working in this repo. Read this before making
changes — several design decisions here look "wrong" in isolation but were
made deliberately to work around real constraints. See the "Decisions and
why" section before refactoring any of them.

## What this is

Infrastructure for a private, short-lived, per-team hackathon environment.
Many teams (target ~80), a small pool of hosts ("nodes"). Each team gets a
fully isolated stack (a "zone").

`BASE_DOMAIN` is the apex where hackzone is hosted (e.g. `x.com`); segregated
subdomains sit under it:

| host | what |
|---|---|
| `admin.<BASE_DOMAIN>` | the control plane (one, `hz-control`) |
| `<zone>.git.<BASE_DOMAIN>` | that zone's Forgejo — git, PRs, Actions, registry |
| `<app>.apps.<BASE_DOMAIN>` | a deployed app (global name, owned by the deploying zone) |

Per zone:

- **git hosting + PRs/issues + CI + container registry** — one Forgejo
  instance at `<zone>.git.<BASE_DOMAIN>` (no separate GitLab/Woodpecker/registry:2)
- **isolated build sandbox** — a `docker:dind` sidecar, so one zone's CI can
  never see another zone's containers/images/network
- **any number of live apps** — each `docker build` + `docker push` + a POST
  to the control plane puts an app at `<app>.apps.<BASE_DOMAIN>`

Two admin roles:

- **Platform admin** — registers **nodes**, creates/places/moves/destroys
  **zones**, sees everything. Uses the `hz` CLI and the control-plane's
  platform view.
- **Zone admin** — one per zone (the team lead). Manages *their* zone only —
  add/remove users, create repos, restart the runner, watch build/deploy
  status — through the control-plane's zone view, which proxies that zone's
  own Forgejo admin API.

## Repo layout

```
hz                             # platform-admin CLI: hz node|zone|app|sweep|control
scripts/
  lib.sh                       # shared: node/zone/app lookups, `zc`, domain helpers
  node.sh                      # hz node add|list|show|drain|undrain|rm
  zone.sh                      # hz zone create|list|show|status|move|destroy
  app.sh                       # hz app list|show|rm
  scheduler.sh                 # pick_node(): least-loaded active node that fits
  provision-zone.sh            # stand up one zone on a node (two-phase; mints tokens + zone-admin)
  teardown-zone.sh             # tear down one zone + all its apps
  sweep-idle.sh                # cron: reclaim zones idle past a TTL
control/
  hz-control.py                # the control plane: platform view, zone-admin surface, CI /deploy + /undeploy
templates/
  zone-compose.yml.tmpl        # per-zone forgejo + dind + runner (envsubst template)
  app-compose.tmpl             # one deployed app (envsubst template)
  traefik-core-compose.yml     # per-node Traefik, 3 certs, runs once per node
examples/deploy.yml            # sample workflow to seed into a zone's repo
state/
  nodes/<name>.env             # node registry (DOCKER_HOST, CPUS, MEM_GB, LABELS, STATE)
  zones/<slug>/                # per-zone: zone.env, docker-compose.yml, runner-secret,
                               #   zone-admin.txt, zone-token, deploy-token, last-activity
  apps/<name>.env              # app registry (APP_NAME, ZONE, NODE, IMAGE, PORT, DEPLOY_ID)
  apps/<name>-compose.yml      # rendered per-app compose
README.md                      # operator-facing overview
```

`state/` is generated, never source; `nodes/`, `zones/`, `apps/` are gitignored.

## Decisions and why (don't relitigate these without reading first)

**"Zone" = one team's isolated stack, assigned 1:1 to a node.** Every per-zone
Docker resource is prefixed `zone-<slug>` (containers, networks, volumes,
compose project) so `docker compose -p zone-<slug> down -v` is a complete,
safe teardown. A node is a host that runs zone stacks; the control host runs
`hz` and `hz-control` and reaches each node's Docker daemon (local socket,
`ssh://`, or `tcp://`+TLS).

**Segregated subdomains — a namespace each for admin, git, and apps — not a
flat `*.<BASE_DOMAIN>`.** Docker hits `/v2/...` at the domain *root* for
registry operations and ignores path prefixes, so a forge at
`<slug>.<domain>/git` would break `docker push` against its own registry. Each
Forgejo therefore owns a whole origin: `<slug>.git.<BASE_DOMAIN>`. Apps live
under their own namespace, `<app>.apps.<BASE_DOMAIN>`, so one zone can run many
of them and each has a clean host. The control plane is one host,
`admin.<BASE_DOMAIN>`.

TLS scope follows from this: a `*.<BASE_DOMAIN>` wildcard is single-label and
misses `<x>.git.<BASE_DOMAIN>` / `<x>.apps.<BASE_DOMAIN>`. So each node's
Traefik holds **three** certs — `admin.<BASE_DOMAIN>`, `*.git.<BASE_DOMAIN>`,
`*.apps.<BASE_DOMAIN>` — and any zone or app on that node is covered with no
per-name request. DNS still routes per name: `<slug>.git.<BASE_DOMAIN>` and
`<app>.apps.<BASE_DOMAIN>` A-records point at whichever node runs that
zone/app (single-node: just wildcard those two). Creating those records is a
known gap.

**App names are global; the first zone to deploy a name owns it.** An app is
`state/apps/<name>.env` recording its zone, node, and image. `hz-control`
rejects a deploy of a name another zone owns, and only runs an image from the
requesting zone's own registry (`<slug>.git.<domain>/…`). Each app is its own
compose project `app-<name>` on the owning zone's node.

**Apps are deployed from the control host onto the zone's node, not from inside
the zone's DinD sandbox.** A container built and run inside a nested Docker
engine is in that engine's private netns — Traefik can't route to it. So CI
builds + pushes an image, then POSTs `hz-control /deploy` (`{app, image,
port}`, per-zone bearer token); the control plane renders `app-compose.tmpl`
and runs it on the zone's node's real daemon, on `traefik-public`.
`POST /undeploy` (or `hz app rm`) tears one down.

**One central control plane, not an agent per node.** `hz-control` orchestrates
across nodes: it holds `HZ_ADMIN_TOKEN` (platform), each zone's `zone-token`
(zone admin) and `deploy-token` (CI), reaches every node's Docker daemon the
same way `hz` does, and reaches every zone's Forgejo over the public
`<slug>.git.<domain>` API with the admin token minted at provisioning. Zone
admins never get node or Docker access — every action is a proxied Forgejo API
call or a `docker compose` command scoped to a `zone-<slug>` / `app-<name>`
project.

**Node placement is CPU-headroom first.** `scheduler.sh` picks the active node
with the most free CPU (capacity minus the sum of assigned zones' quota
limits) that can fit the zone and carries any required label. Quotas are
limits, not reservations, so nodes oversubscribe — but a zone whose limits
alone exceed a node is never placed there. `--node` overrides the scheduler.

**Runner registration is a two-phase apply, and the runner config is pushed
in with `docker compose cp`.** Forgejo needs its DB (first-run init) before
`forgejo forgejo-cli actions register --secret <40 hex>` can run, so
`provision-zone.sh` brings up `forgejo`+`dind`, waits for health, registers,
then brings up the `runner` and `docker compose cp`s the generated
`runner-config.yml` into its `/data` volume. `cp` (not a bind mount) so it
works whether the node is local or reached over SSH. The runner's UUID is
derived from the secret locally (`forgejo_uuid` in `lib.sh`) — first 16 chars,
as bytes, hex, `8-4-4-4-12` — so we don't parse the register command's stdout.

## Conventions

- Templates are rendered with `envsubst`, told the exact var list (see
  `*_TMPL_VARS` in `lib.sh`), never a templating engine. If a template needs
  conditionals/loops, it should become a script that generates the file
  (that's why `runner-config.yml` is written by `provision-zone.sh`, not
  templated).
- Per-zone resource limits are env vars with defaults at the top of
  `provision-zone.sh` (`CPU_FORGEJO`, `MEM_DIND`, …) — change quotas there.
- `hz-control` and the scripts are stdlib / shell only. No frameworks.
- `state/` is generated — never hand-edit; re-run `provision-zone.sh`.

## Known gaps (see README "rough edges" for the full list)

- **Nothing creates the `<slug>.git.<BASE_DOMAIN>` / `<app>.apps.<BASE_DOMAIN>`
  DNS records.** Provisioning and deploy assume they resolve (wildcard those
  two namespaces for a single node; per-record across nodes). Wiring it to the
  DNS-provider API Traefik already uses is the top task.
- **`traefik-public` is one flat network.** The app containers and the Forgejo
  instances on a node can reach each other by IP; the untrusted code is the
  app container. A Traefik-per-zone network or an L3 policy would close it.
- **`hz-control` runs bare** with Docker access and every token on disk. It
  needs a systemd unit / locked-down container and its own TLS front.
- **No repo seeding.** The starter repo, `deploy.yml`, and repo vars/secrets
  are still set up by hand per zone.
- **No roster loop.** 80 zones is 80 `hz zone create` calls.

## Likely next tasks

1. `hz zone create` and `/deploy` create the `<slug>.git` / `<app>.apps` DNS
   records via the DNS-provider API, and `hz zone create` seeds the starter
   repo (`deploy.yml` + repo vars/secrets) through the zone's Forgejo API — so
   a zone, and then an app, is usable end to end from one command.
2. `hz roster apply <file>` / `hz roster teardown` — one-shot event start/end.
3. Package `hz-control` as a systemd unit or container behind the node Traefik
   at `admin.<BASE_DOMAIN>`, with the CI-only `/deploy` path separated from the
   cookie-authed UI.
4. Zone-level quota requests (zone admin asks, platform admin approves) and
   `hz zone move` that carries the Forgejo data volume.
