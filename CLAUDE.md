# CLAUDE.md

Context for Claude Code when working in this repo. Read this before making
changes — several design decisions here look "wrong" in isolation but were
made deliberately to work around real constraints. See the "Decisions and
why" section before refactoring any of them.

## What this is

Infrastructure for a private, short-lived, per-team hackathon environment.
Many teams (target ~80), a small pool of hosts ("nodes"). Each team gets a
fully isolated stack (a "zone").

`BASE_DOMAIN` is the apex where ignition is hosted (e.g. `ignition.example`). Each zone
owns the whole `<slug>.<BASE_DOMAIN>` subtree; the platform admin sits on the
apex:

| host | what |
|---|---|
| `admin.<BASE_DOMAIN>` | the platform admin control plane (one, `ign-control`) |
| `git.<slug>.<BASE_DOMAIN>` | that zone's Forgejo — git, PRs, Actions, registry |
| `admin.<slug>.<BASE_DOMAIN>` | that zone's admin view (same `ign-control`, zone-scoped) |
| `<app>.apps.<slug>.<BASE_DOMAIN>` | a deployed app (name unique within the zone) |

`ignition.example` in the docs is a placeholder; `BASE_DOMAIN` is whatever apex the
deploying org controls, provided its DNS can serve names two labels deep and it
can obtain `*.<slug>.<apex>` wildcard certs.

Per zone:

- **git hosting + PRs/issues + CI + container registry** — one Forgejo
  instance at `git.<slug>.<BASE_DOMAIN>` (no separate GitLab/Woodpecker/registry:2)
- **isolated build sandbox** — a `docker:dind` sidecar, so one zone's CI can
  never see another zone's containers/images/network
- **any number of live apps** — each `docker build` + `docker push` + a POST
  to the control plane puts an app at `<app>.apps.<slug>.<BASE_DOMAIN>`

Two admin roles:

- **Platform admin** — registers **nodes**, creates/places/moves/destroys
  **zones**, sees everything. Uses the `ign` CLI and the control-plane's
  platform view.
- **Zone admin** — one per zone (the team lead). Manages *their* zone only —
  add/remove users, create repos, **cut releases** (the zone view's *Release*
  button derives the bump from the commits since the last release and tags the
  next `vX.Y.Z` on `main` — a dropdown overrides), restart the runner, watch
  build/deploy status — through the control-plane's zone view, which proxies
  that zone's own Forgejo admin API.

## Repo layout

```
ign                             # platform-admin CLI: ign node|zone|app|sweep|control
scripts/
  lib.sh                       # shared: node/zone/app lookups, `zc`, domain helpers
  node.sh                      # ign node add|list|show|drain|undrain|rm
  zone.sh                      # ign zone create|list|show|status|move|destroy
  app.sh                       # ign app list|show <zone> <name>|rm <zone> <name>
  scheduler.sh                 # pick_node(): least-loaded active node that fits
  provision-zone.sh            # stand up one zone on a node (two-phase; mints tokens + zone-admin)
  teardown-zone.sh             # tear down one zone + all its apps
  sweep-idle.sh                # cron: reclaim zones idle past a TTL
control/
  ign-control.py                # the control plane: platform view, zone-admin surface, CI /deploy + /undeploy
templates/
  zone-compose.yml.tmpl        # per-zone forgejo + dind + runner (envsubst template)
  app-compose.tmpl             # one deployed app (envsubst template)
  traefik-core-compose.yml     # per-node core: Traefik (apex cert + file provider) + Watchtower, runs once per node
examples/deploy.yml            # sample workflow to seed into a zone's repo
state/
  nodes/<name>.env             # node registry (DOCKER_HOST, CPUS, MEM_GB, LABELS, STATE)
  zones/<slug>/                # per-zone: zone.env, docker-compose.yml, runner-secret,
                               #   zone-admin.txt, zone-token, deploy-token, last-activity
  zones/<slug>/apps/<name>.env # app registry (APP_NAME, ZONE, NODE, IMAGE, PORT, DEPLOY_ID)
  zones/<slug>/apps/<name>-compose.yml   # rendered per-app compose
  control/dynamic/*.yml        # Traefik file-provider snippets: admin.<BASE_DOMAIN> +
                               #   admin.<slug>.<BASE_DOMAIN> -> ign-control
README.md                      # operator-facing overview
```

`state/` is generated, never source; `nodes/`, `zones/`, `control/` are gitignored.

## Decisions and why (don't relitigate these without reading first)

**"Zone" = one team's isolated stack, assigned 1:1 to a node.** Every per-zone
Docker resource is prefixed `zone-<slug>` (containers, networks, volumes,
compose project) so `docker compose -p zone-<slug> down -v` is a complete,
safe teardown. A node is a host that runs zone stacks; the control host runs
`ign` and `ign-control` and reaches each node's Docker daemon (local socket,
`ssh://`, or `tcp://`+TLS).

**One subtree per zone — `<slug>.<BASE_DOMAIN>` — not a flat `*.<BASE_DOMAIN>`.**
Docker hits `/v2/...` at the domain *root* for registry operations and ignores
path prefixes, so a forge at `<slug>.<domain>/git` would break `docker push`
against its own registry. Each Forgejo therefore owns a whole origin,
`git.<slug>.<BASE_DOMAIN>`. Everything else for the zone hangs off the same
subtree: `admin.<slug>.<BASE_DOMAIN>` (the zone-admin view) and
`<app>.apps.<slug>.<BASE_DOMAIN>` (one per deployed app). The platform admin is
one host on the apex, `admin.<BASE_DOMAIN>`.

TLS scope follows from this: a `*.<BASE_DOMAIN>` wildcard is single-label and
misses anything two labels deep. So the control host's Traefik holds the apex
cert (`<BASE_DOMAIN>` + `*.<BASE_DOMAIN>`, which covers `admin.<BASE_DOMAIN>`),
and **each zone's own Forgejo router** additionally requests
`*.<slug>.<BASE_DOMAIN>` + `*.apps.<slug>.<BASE_DOMAIN>` — covering that zone's
git, admin, and every app with no per-name request. `admin.<slug>.<BASE_DOMAIN>`
is served by `ign-control` behind the control host's Traefik via a file-provider
snippet (`state/control/dynamic/<slug>.yml`, written by `provision-zone.sh`)
that carries its own `*.<slug>.<BASE_DOMAIN>` cert request. DNS routes per name:
`git.<slug>` / `*.apps.<slug>` A-records point at the node running that zone,
`admin.<slug>` and `admin.<BASE_DOMAIN>` at the control host (single-host:
wildcard `*.<slug>.<BASE_DOMAIN>`). Creating those records is a known gap.

**App names are unique within a zone, not globally.** An app is
`state/zones/<slug>/apps/<name>.env` recording its zone, node, and image.
`ign-control` only runs an image from the requesting zone's own registry
(`git.<slug>.<domain>/…`). Each app is its own compose project
`app-<slug>-<name>` on the zone's node.

**Apps are deployed from the control host onto the zone's node, not from inside
the zone's DinD sandbox.** A container built and run inside a nested Docker
engine is in that engine's private netns — Traefik can't route to it. So CI
builds + pushes an image, then POSTs `ign-control /deploy` (`{app, image,
port}`, per-zone bearer token); the control plane renders `app-compose.tmpl`
and runs it on the zone's node's real daemon, on `traefik-public`.
`POST /undeploy` (or `ign app rm`) tears one down.

**Deploys come only from a release — never a plain push to `main`.** The CI
workflow (`examples/deploy.yml`) triggers on a git tag only. Tags are created
by the zone console's **Release** button: `cut_release()` in `ign-control.py`
diffs the last tag against `main`, runs `classify_bump()` over those commit
messages (Conventional Commits: `feat!:`/`BREAKING CHANGE:` → major, `feat:` →
minor, else patch; a `bump=` override skips this), and creates the next
`vMAJOR.MINOR.PATCH` tag on `main` (first release `v0.1.0` or `v1.0.0`). The
zone admin never types a version. Each run pushes `:<sha>` (immutable) +
`:<tag>` and `POST /deploy`s `:<tag>`, rolling the app forward immediately.
Independently, a **per-node Watchtower** (in `traefik-core-compose.yml`,
`--label-enable`, 60s poll) pulls a new digest for any container labelled
`com.centurylinklabs.watchtower.enable=true` — which `app-compose.tmpl` sets on
every app ign-control deploys, so teams get auto-reload without touching their
repo — and never touches Traefik/Forgejo/DinD/runners.

**One central control plane, not an agent per node.** `ign-control` orchestrates
across nodes: it holds `IGN_ADMIN_TOKEN` (platform), each zone's `zone-token`
(zone admin) and `deploy-token` (CI), reaches every node's Docker daemon the
same way `ign` does, and reaches every zone's Forgejo over the public
`git.<slug>.<domain>` API with the admin token minted at provisioning. Zone
admins never get node or Docker access — every action is a proxied Forgejo API
call or a `docker compose` command scoped to a `zone-<slug>` / `app-<slug>-<name>`
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
- `ign-control` and the scripts are stdlib / shell only. No frameworks.
- `state/` is generated — never hand-edit; re-run `provision-zone.sh`.

## Known gaps (see README "rough edges" for the full list)

- **Nothing creates the `git.<slug>.<BASE_DOMAIN>` / `<app>.apps.<slug>.<BASE_DOMAIN>` / `admin.<slug>.<BASE_DOMAIN>`
  DNS records.** Provisioning and deploy assume they resolve (wildcard those
  two namespaces for a single node; per-record across nodes). Wiring it to the DNS-provider API Traefik already uses is the top task.
- **`traefik-public` is one flat network.** The app containers and the Forgejo
  instances on a node can reach each other by IP; the untrusted code is the
  app container. A Traefik-per-zone network or an L3 policy would close it.
- **`ign-control` runs bare** with Docker access and every token on disk. It
  needs a systemd unit / locked-down container and its own TLS front.
- **No repo seeding.** The starter repo, `deploy.yml`, and repo vars/secrets
  are still set up by hand per zone.
- **No roster loop.** 80 zones is 80 `ign zone create` calls.
- **No services catalogue.** A team that needs Postgres, a mock of an internal
  API, or a keyed proxy to an external one stands it up by hand. See task 5.

## Likely next tasks

1. `ign zone create` and `/deploy` create the `git.<slug>` / `<app>.apps.<slug>` DNS
   records via the DNS-provider API, and `ign zone create` seeds the starter
   repo (`deploy.yml` + repo vars/secrets) through the zone's Forgejo API — so
   a zone, and then an app, is usable end to end from one command.
2. `ign roster apply <file>` / `ign roster teardown` — one-shot event start/end.
3. Package `ign-control` as a systemd unit or container behind the control-host Traefik
   at `admin.<BASE_DOMAIN>`, with the CI-only `/deploy` path separated from the
   cookie-authed UI.
4. Zone-level quota requests (zone admin asks, platform admin approves) and
   `ign zone move` that carries the Forgejo data volume.
5. **A per-zone services catalogue** (`ign svc` / a "Services" section in the
   console). A curated set of containerised services a zone deploys into itself
   with one click, so a team doesn't hand-roll its backing infrastructure.
   Two kinds:
   - **Backing services** — Postgres, Redis, MinIO, Mailhog, a canned mock of
     an internal API. Rendered from `catalogue/<name>.compose.tmpl` (same
     `envsubst` discipline as `app-compose.tmpl`) onto a per-zone `svc-<slug>`
     network that the zone's own apps also join, with **no Traefik router** —
     reachable only by that zone's apps, by DNS name.
   - **Credentialed proxies** — a small proxy container in front of a real
     internal or external service (the corp LLM gateway, a payments sandbox, an
     internal data API) that injects an org-held key `ign-control` stores (same
     model as `deploy-token` / `zone-admin.txt`). The team gets an endpoint on
     `svc-<slug>`; the real key never reaches their repo or laptop, and the
     platform can meter / rotate / revoke it per zone.
   Compose project `svc-<slug>-<name>`, torn down with the zone. Catalogue
   entries live in `catalogue/` (or a pinned catalogue repo): compose template
   plus a manifest — ports, env, which secrets it needs from `ign-control`, and
   whether it's backing-only or routed.
