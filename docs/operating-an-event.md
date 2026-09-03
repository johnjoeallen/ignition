# Operating an Event

## Prerequisites

- A **control host** with Docker + Compose v2, `envsubst` (gettext),
  `openssl`, `bash`, Python 3.11+, and a way to reach each node's Docker
  daemon (local socket, `ssh://`, or `tcp://`+TLS).
- **Nodes** — hosts with Docker, each with the shared network created:
  `docker network create traefik-public`.
- DNS: `*.<event-domain>` → the node(s) (covers every zone's live app). Each
  zone's Forgejo is at `git.<slug>.<event-domain>` — two labels deep, so
  `*.<event-domain>` misses it; add a `*.<slug>.<event-domain>` record per zone.
- A DNS-provider API token for Traefik's ACME DNS challenge (Cloudflare by
  default; swap the provider in `templates/traefik-core-compose.yml`).

Traefik terminates TLS for the apps and the Forgejo instances, so no
`insecure-registries` entry is needed anywhere.

## Standing up the event

```sh
# 1. Traefik — once per node.
export BASE_DOMAIN=hz.example.com ACME_EMAIL=ops@example.com CF_DNS_API_TOKEN=...
docker compose -f templates/traefik-core-compose.yml up -d

# 2. The control plane — once, on the control host. Front it with Traefik / a
#    tunnel; do not expose it raw.
export HZ_ADMIN_TOKEN=$(openssl rand -hex 32)      # keep this — it's the platform key
./hz control &

# 3. Register nodes.
./hz node add node-1 local              --cpus 32 --mem 128g
./hz node add node-2 ssh://ops@10.0.0.2 --cpus 32 --mem 128g --labels fast
./hz node list

# 4. Create zones (scheduler picks a node; --node to pin, --label to constrain).
BASE_DOMAIN=hz.example.com ./hz zone create alpha
BASE_DOMAIN=hz.example.com ./hz zone create bravo --node node-2
#   → Forgejo   https://git.alpha.hz.example.com/
#   → live app  https://alpha.hz.example.com/
#   → state/zones/alpha/{zone-admin.txt, zone-token, deploy-token}
```

Hand each team lead their zone's **`zone-admin.txt`** (Forgejo admin login) and
**`zone-token`** (control-plane zone view). They seed the starter repo with
`.forgejo/workflows/deploy.yml` (from `examples/deploy.yml`) and set its repo
variables/secrets — `REGISTRY`, `CONTROL_URL`, `APP_PORT`, `FORGEJO_TOKEN`,
`DEPLOY_TOKEN`. A push to `main` then builds, pushes, and deploys.

## During the event

```sh
./hz zone list
./hz zone status alpha              # live container health on alpha's node
./hz zone move alpha --node node-1  # rebuild on another node (data does not follow)
./hz zone destroy alpha             # complete: containers, volumes, state
./hz node drain node-2              # stop placing new zones here
./hz sweep                          # reclaim idle zones now (or cron sweep-idle.sh)
```

`sweep-idle.sh` reads `state/zones/<slug>/last-activity`, bumped by
`hz zone create` and every deploy. Cron it (every ~15 min) so abandoned zones
free their node capacity automatically.

## Capacity

Per-zone quotas have defaults at the top of `provision-zone.sh`:

| Var | Default | Notes |
|---|---|---|
| `CPU_FORGEJO` / `MEM_FORGEJO` | `1.0` / `1g` | idle Forgejo is light; bursts during Actions |
| `CPU_DIND` / `MEM_DIND` | `2.0` / `4g` | image builds are the heavy part |
| `CPU_RUNNER` / `MEM_RUNNER` | `1.0` / `2g` | |
| `CPU_APP` / `MEM_APP` | `1.0` / `1g` | the live demo container |

The scheduler sums these limits as a zone's "footprint" for node accounting.
They are **limits**, not reservations — zones are bursty and rarely build at
the same instant, so nodes safely oversubscribe. As a rough guide, budget
~2–3 GB steady-state memory per active zone and size each node for the number
building concurrently, not the total. `hz node list` shows allocated vs.
capacity per node.

## Rough edges

- **`git.<slug>.<event-domain>` DNS isn't created for you** — provisioning
  assumes it resolves. Wiring it to the DNS-provider API is the top task.
- **`traefik-public` is one flat network** — live-app containers and Forgejo
  instances on a node can reach each other by IP.
- **`hz-control` runs bare** with Docker access and every token on disk — it
  needs a systemd unit / locked-down container and its own TLS front.
- **The control plane pulls images anonymously** — private packages need
  `docker login` / a per-zone `DOCKER_CONFIG` on the node.
- **No repo seeding, no roster loop** — both are top items in `CLAUDE.md`.
