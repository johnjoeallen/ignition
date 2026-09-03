# hackzone-one

Per-team hackathon infrastructure. Many teams (target ~80), a pool of hosts
("nodes"). Each team gets a fully isolated stack (a "zone").

`BASE_DOMAIN` is the apex where hackzone is hosted (e.g. `x.com`). Segregated
subdomains sit under it:

| host | what |
|---|---|
| `admin.x.com` | the control plane (`hz-control`) |
| `<zone>.git.x.com` | that zone's Forgejo — git, PRs, Actions, container registry |
| `<app>.apps.x.com` | a deployed app — a zone can run many; names are global |

Per zone: one Forgejo, a private Docker-in-Docker build engine so one zone's CI
can never touch another's, and any number of live apps that its CI deploys on
every push.

📖 **[Concept, executive overview, and architecture →
johnjoeallen.github.io/hackzone-one](https://johnjoeallen.github.io/hackzone-one/)**

Read **[CLAUDE.md](CLAUDE.md)** for the design decisions before changing anything.

## Roles

- **Platform admin** — registers nodes, creates/places/moves/destroys zones,
  sees every zone and app. Uses the `hz` CLI and `admin.x.com`.
- **Zone admin** — one per zone (the team lead). Adds users, creates repos,
  restarts the runner, manages the zone's apps — for *their* zone only, at
  `admin.x.com` (or Forgejo directly).

**Forgejo, not Gitea:** community-governed (Codeberg e.V.), FOSS-first, no
open-core drift. Server `codeberg.org/forgejo/forgejo:11` (LTS), runner
`code.forgejo.org/forgejo/runner:13`.

## Prerequisites

- A control host with Docker + Docker Compose v2, `envsubst` (gettext),
  `openssl`, `bash`, Python 3.11+, and a way to reach each node's Docker
  daemon (local socket, `ssh://`, or `tcp://`+TLS).
- Nodes: hosts with Docker; `docker network create traefik-public` on each.
- DNS: `<zone>.git.x.com` and `<app>.apps.x.com` must resolve to whichever
  node runs them. On a single node, `*.git.x.com` and `*.apps.x.com` A-records
  are enough; across nodes, per-record (a top next task to automate).
- A DNS-provider API token for Traefik's ACME DNS challenge — each node's
  Traefik fetches `admin.x.com`, `*.git.x.com`, and `*.apps.x.com` certs.

Traefik terminates TLS everywhere, so no `insecure-registries` entry is needed.

## Quickstart

```sh
# 1. Traefik — once per node.
export BASE_DOMAIN=x.com ACME_EMAIL=ops@x.com CF_DNS_API_TOKEN=...
docker compose -f templates/traefik-core-compose.yml up -d

# 2. The control plane, once — front it with Traefik at admin.x.com / a tunnel.
HZ_ADMIN_TOKEN=$(openssl rand -hex 32) ./hz control &

# 3. Register nodes.
./hz node add node-1 local              --cpus 32 --mem 128g
./hz node add node-2 ssh://ops@10.0.0.2 --cpus 32 --mem 128g

# 4. Create a zone (scheduler picks a node; --node to pin, --label to constrain).
BASE_DOMAIN=x.com ./hz zone create alpha
#   -> Forgejo   https://alpha.git.x.com/
#   -> apps      https://<name>.apps.x.com/   (per app CI deploys)
#   -> state/zones/alpha/{zone-admin.txt, zone-token, deploy-token}

./hz zone list
./hz zone status alpha
./hz app list                 # every deployed app across all zones
./hz zone destroy alpha       # zone + all its apps
```

A zone lead then seeds a repo with `.forgejo/workflows/deploy.yml`
([`examples/deploy.yml`](examples/deploy.yml)) and its vars/secrets
(`REGISTRY`, `CONTROL_URL`, `APP_NAME`, `APP_PORT`, `FORGEJO_TOKEN`,
`DEPLOY_TOKEN`). A push to `main` builds, pushes to `alpha.git.x.com`, and
deploys `APP_NAME.apps.x.com`. Several repos → several apps.

## Layout

| path | what |
|---|---|
| `hz` | platform CLI: `hz node \| zone \| app \| sweep \| control` |
| `control/hz-control.py` | control plane — platform view, zone-admin surface, CI `/deploy` + `/undeploy` |
| `templates/traefik-core-compose.yml` | per-node Traefik (admin + `*.git` + `*.apps` certs) |
| `templates/zone-compose.yml.tmpl` | per-zone Forgejo + DinD + runner |
| `templates/app-compose.tmpl` | one deployed app |
| `scripts/{node,zone,app,scheduler,provision-zone,teardown-zone,sweep-idle}.sh` | the CLI internals |
| `examples/deploy.yml` | sample CI workflow |
| `state/{nodes,zones,apps}/` | generated — never hand-edit |

## Rough edges

- **DNS records for `<slug>.git` / `<app>.apps` aren't created for you** —
  wildcard both namespaces for one node; automate per-record across nodes.
- **`traefik-public` is one flat network** on a node — app and Forgejo
  containers can reach each other by IP.
- **`hz-control` runs bare** with Docker access and every token on disk — it
  needs a systemd unit / locked-down container behind `admin.x.com`.
- **The control plane pulls images anonymously** — private packages need
  `docker login` / a per-zone `DOCKER_CONFIG` on the node.
- **No repo seeding, no roster loop** — both are top next tasks (`CLAUDE.md`).
