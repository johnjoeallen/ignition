# hackzone-one

Per-team hackathon infrastructure. Many teams (target ~80), a pool of hosts
("nodes"). Each team gets a fully isolated stack (a "zone"):

- **Forgejo** — git hosting, PRs/issues, Actions CI, and a container registry,
  all from one instance at `https://git.<slug>.<BASE_DOMAIN>/`
- **private DinD** — a per-zone Docker-in-Docker engine so one zone's CI can
  never touch another's images, containers or network
- **a live demo app** — `https://<slug>.<BASE_DOMAIN>/`, deployed by CI and
  routed by the zone's node Traefik (which also fronts the Forgejo above)

Zones are placed on nodes by a scheduler, and provisioned / torn down
independently.

📖 **[Concept, executive overview, and architecture →
johnjoeallen.github.io/hackzone-one](https://johnjoeallen.github.io/hackzone-one/)**

Read **[CLAUDE.md](CLAUDE.md)** for the design decisions before changing anything.

## Roles

- **Platform admin** — registers nodes, creates/places/moves/destroys zones,
  sees everything. Uses the `hz` CLI and the control-plane's platform view.
- **Zone admin** — one per zone (the team lead). Adds users, creates repos,
  restarts the runner, watches build/deploy status — for *their* zone only,
  through the control-plane's zone view.

**Forgejo, not Gitea:** community-governed (Codeberg e.V.), FOSS-first, no
open-core drift. Server `codeberg.org/forgejo/forgejo:11` (LTS), runner
`code.forgejo.org/forgejo/runner:13`.

## Prerequisites

- A control host with Docker + Docker Compose v2, `envsubst` (gettext),
  `openssl`, `bash`, Python 3.11+, and a way to reach each node's Docker
  daemon (local socket, `ssh://`, or `tcp://`+TLS).
- Nodes: hosts with Docker; the `traefik-public` network created on each
  (`docker network create traefik-public`).
- DNS: `*.<BASE_DOMAIN>` → the host(s) (covers the live apps). Each zone's
  Forgejo is at `git.<slug>.<BASE_DOMAIN>` — two labels deep, so
  `*.<BASE_DOMAIN>` misses it; add a `*.<slug>.<BASE_DOMAIN>` record per zone.
- A DNS-provider API token for Traefik's ACME DNS challenge (it fetches the
  `*.<BASE_DOMAIN>` cert plus a `*.<slug>.<BASE_DOMAIN>` cert per zone).

Traefik terminates TLS for both the apps and the Forgejo instances, so no
`insecure-registries` entry is needed.

## Quickstart

```sh
# 1. Host Traefik, once per node.
export BASE_DOMAIN=hz.example.com ACME_EMAIL=ops@example.com CF_DNS_API_TOKEN=...
docker compose -f templates/traefik-core-compose.yml up -d

# 2. The control plane, once — front it with Traefik / a tunnel, not raw.
HZ_ADMIN_TOKEN=$(openssl rand -hex 32) ./hz control &

# 3. Register nodes.
./hz node add node-1 local              --cpus 32 --mem 128g
./hz node add node-2 ssh://ops@10.0.0.2 --cpus 32 --mem 128g

# 4. Create a zone (scheduler picks a node; or --node node-2).
BASE_DOMAIN=hz.example.com ./hz zone create alpha
#   -> Forgejo   https://git.alpha.hz.example.com/
#   -> live app  https://alpha.hz.example.com/   (once CI deploys)
#   -> zone-admin login   state/zones/alpha/zone-admin.txt
#   -> zone control token state/zones/alpha/zone-token
#   -> CI deploy token    state/zones/alpha/deploy-token

./hz zone list
./hz zone status alpha
./hz zone move alpha --node node-1
./hz zone destroy alpha
```

Then the **zone admin** signs in to the control plane with the zone token and
uses the zone view to add teammates, create repos, and restart the runner; or
does the same in Forgejo directly with the `zone-admin.txt` login. Seed the
repo with `.forgejo/workflows/deploy.yml` (from [`examples/deploy.yml`](examples/deploy.yml))
and its vars/secrets, and a push to `main` builds, pushes, and deploys.

## Layout

| path | what |
|---|---|
| `hz` | platform-admin CLI: `hz node \| zone \| sweep \| control` |
| `control/hz-control.py` | control plane — platform view, zone-admin surface, CI `/deploy` bridge |
| `templates/traefik-core-compose.yml` | host Traefik, wildcard TLS |
| `templates/zone-compose.yml.tmpl` | per-zone Forgejo + DinD + runner |
| `templates/app-compose.zone.yml.tmpl` | per-zone live app (applied by the control plane) |
| `scripts/node.sh` / `zone.sh` | `hz node …` / `hz zone …` implementations |
| `scripts/scheduler.sh` | `pick_node()` — least-loaded active node that fits |
| `scripts/provision-zone.sh` / `teardown-zone.sh` | one zone, on its node |
| `scripts/sweep-idle.sh` | cron: reclaim zones idle past `IDLE_TTL` |
| `examples/deploy.yml` | sample CI workflow |
| `state/nodes/`, `state/zones/<slug>/` | generated — never hand-edit |

## Rough edges

- **Nothing creates the `git.<slug>.<BASE_DOMAIN>` DNS record.** Provisioning
  assumes it resolves (a `*.<slug>.<BASE_DOMAIN>` record, or one per zone).
- **`traefik-public` is one flat network** — the live-app containers and the
  Forgejo instances can reach each other by IP.
- **`hz-control` runs bare** with Docker access and every token on disk — it
  needs a systemd unit / locked-down container and its own TLS front.
- **The control plane pulls images anonymously.** Public packages work;
  private ones need `docker login` / a per-zone `DOCKER_CONFIG` on the node.
- **No repo seeding, no roster loop.** Both are top next tasks (`CLAUDE.md`).
