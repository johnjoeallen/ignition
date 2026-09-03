# hackzone-one

Per-team hackathon infrastructure. Many teams (target ~80), a pool of hosts
("nodes"). Each team gets a fully isolated stack (a "zone").

`BASE_DOMAIN` is the apex where hackzone is hosted (e.g. `hackzone.com`). Each zone
owns the whole `<slug>.hackzone.com` subtree; the platform admin sits on the apex:

| host | what |
|---|---|
| `admin.hackzone.com` | the platform admin control plane (`hz-control`) |
| `git.<slug>.hackzone.com` | that zone's Forgejo — git, PRs, Actions, container registry |
| `admin.<slug>.hackzone.com` | that zone's admin view (same `hz-control`, zone-scoped) |
| `<app>.apps.<slug>.hackzone.com` | a deployed app — a zone can run many; names are unique within the zone |

`hackzone.com` is a placeholder — set `BASE_DOMAIN` to any apex your
organisation controls, as long as its DNS can serve records two labels deep
(`git.<slug>.<apex>`) and you can get wildcard certs for `*.<slug>.<apex>`.

Per zone: one Forgejo, a private Docker-in-Docker build engine so one zone's CI
can never touch another's, and any number of live apps that its CI deploys on
every push.

📖 **[Concept, executive overview, and architecture →
johnjoeallen.github.io/hackzone-one](https://johnjoeallen.github.io/hackzone-one/)**

Read **[CLAUDE.md](CLAUDE.md)** for the design decisions before changing anything.

## Roles

- **Platform admin** — registers nodes, creates/places/moves/destroys zones,
  sees every zone and app. Uses the `hz` CLI and `admin.hackzone.com`.
- **Zone admin** — one per zone (the team lead). Adds users, creates repos,
  restarts the runner, manages the zone's apps — for *their* zone only, at
  `admin.<slug>.hackzone.com` (or Forgejo directly).

**Forgejo, not Gitea:** community-governed (Codeberg e.V.), FOSS-first, no
open-core drift. Server `codeberg.org/forgejo/forgejo:11` (LTS), runner
`code.forgejo.org/forgejo/runner:13`.

## Prerequisites

- A control host with Docker + Docker Compose v2, `envsubst` (gettext),
  `openssl`, `bash`, Python 3.11+, and a way to reach each node's Docker
  daemon (local socket, `ssh://`, or `tcp://`+TLS).
- Nodes: hosts with Docker; `docker network create traefik-public` on each.
- DNS: `git.<slug>.hackzone.com` and `*.apps.<slug>.hackzone.com` must resolve to the node
  running that zone; `admin.<slug>.hackzone.com` to the control host. A single
  `*.<slug>.hackzone.com` A-record covers all three when the zone shares the control
  host; otherwise split it. `admin.hackzone.com` → the control host.
- A DNS-provider API token for Traefik's ACME DNS challenge. The control host's
  Traefik fetches the apex cert (`hackzone.com` + `*.hackzone.com`); each zone's Forgejo
  router fetches `*.<slug>.hackzone.com` + `*.apps.<slug>.hackzone.com` (two labels deep, so
  the apex wildcard misses them).

Traefik terminates TLS everywhere, so no `insecure-registries` entry is needed.

## Quickstart

```sh
# 1. Core services (Traefik + Watchtower) — once per node.
export BASE_DOMAIN=hackzone.com ACME_EMAIL=ops@hackzone.com CF_DNS_API_TOKEN=...
docker compose -f templates/traefik-core-compose.yml up -d

# 2. The control plane, once, on the control host — its Traefik fronts it at
#    admin.hackzone.com and admin.<slug>.hackzone.com via state/control/dynamic/.
HZ_ADMIN_TOKEN=$(openssl rand -hex 32) BASE_DOMAIN=hackzone.com ./hz control &

# 3. Register nodes.
./hz node add node-1 local              --cpus 32 --mem 128g
./hz node add node-2 ssh://ops@10.0.0.2 --cpus 32 --mem 128g

# 4. Create a zone (scheduler picks a node; --node to pin, --label to constrain).
BASE_DOMAIN=hackzone.com ./hz zone create alpha
#   -> Forgejo     https://git.alpha.hackzone.com/
#   -> zone admin  https://admin.alpha.hackzone.com/
#   -> apps        https://<name>.apps.alpha.hackzone.com/   (CI deploys on push to main or a git tag)
#   -> state/zones/alpha/{zone-admin.txt, zone-token, deploy-token}

./hz zone list
./hz zone status alpha
./hz app list                 # every deployed app across all zones
./hz zone destroy alpha       # zone + all its apps
```

A zone lead then seeds a repo with `.forgejo/workflows/deploy.yml`
([`examples/deploy.yml`](examples/deploy.yml)) and its vars/secrets
(`REGISTRY`, `CONTROL_URL`, `APP_NAME`, `APP_PORT`, `DEPLOY_TOKEN`, and an
optional `FORGEJO_TOKEN`). A push to `main` **or a release cut in the Forgejo web UI**
(Releases → New release, target `main` — a zone-admin task, so tags come from
reviewed history, not `git push --tags`) builds, pushes to
`git.alpha.hackzone.com`, and deploys `APP_NAME.apps.alpha.hackzone.com`.
Several repos → several apps.

Builds normally **start from a release** — the zone admin cuts one in the
Forgejo web UI (the zone view links straight to it) and CI builds + ships that
tag; a push to `main` works too. After that, rollout is automatic two ways:
the workflow's `POST /deploy` rolls the app forward immediately, and
**hz-control wires a Watchtower agent into every deployed app** (the
`app-compose.tmpl` label is added for you) so the per-node Watchtower pulls a
new digest for that tag on its own (~60s poll) — a re-push or a base-image
rebuild goes live without another workflow run.

## Layout

| path | what |
|---|---|
| `hz` | platform CLI: `hz node \| zone \| app \| sweep \| control` |
| `control/hz-control.py` | control plane — platform view, zone-admin surface, CI `/deploy` + `/undeploy` |
| `templates/traefik-core-compose.yml` | per-node core: Traefik (apex cert + file provider for `admin.*`) + Watchtower (auto-rolls deployed apps) |
| `templates/zone-compose.yml.tmpl` | per-zone Forgejo + DinD + runner |
| `templates/app-compose.tmpl` | one deployed app |
| `scripts/{node,zone,app,scheduler,provision-zone,teardown-zone,sweep-idle}.sh` | the CLI internals |
| `examples/deploy.yml` | sample CI workflow |
| `state/{nodes,zones,control}/` | generated — never hand-edit |

## Rough edges

- **DNS records for `git.<slug>` / `admin.<slug>` / `*.apps.<slug>` aren't
  created for you** — wildcard `*.<slug>.hackzone.com` for one node; automate
  per-record across nodes.
- **`traefik-public` is one flat network** on a node — app and Forgejo
  containers can reach each other by IP.
- **`hz-control` runs bare** with Docker access and every token on disk — it
  needs a systemd unit / locked-down container behind `admin.hackzone.com`.
- **The control plane and Watchtower pull images anonymously** — private
  packages need `docker login git.<slug>.hackzone.com` on the node (Watchtower
  reads `${DOCKER_CONFIG_DIR:-/root/.docker}/config.json`).
- **No repo seeding, no roster loop** — both are top next tasks (`CLAUDE.md`).
