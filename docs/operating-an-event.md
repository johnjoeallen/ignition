# Operating an Event

## Prerequisites

- A **control host** with Docker + Compose v2, `envsubst` (gettext),
  `openssl`, `bash`, Python 3.11+, and a way to reach each node's Docker
  daemon (local socket, `ssh://`, or `tcp://`+TLS).
- **Nodes** — hosts with Docker, each with the shared network created:
  `docker network create traefik-public`.
- **DNS** (`BASE_DOMAIN` is the apex, e.g. `hackzone.com`):
  `admin.<BASE_DOMAIN>` → the control host, and `git.<slug>.<BASE_DOMAIN>` /
  `admin.<slug>.<BASE_DOMAIN>` / `<app>.apps.<slug>.<BASE_DOMAIN>` → whichever
  node runs that zone. On a single node, `*.<slug>.<BASE_DOMAIN>` A-records cover it; across
  nodes, a record per zone/app.
- A DNS-provider API token for Traefik's ACME DNS challenge (Cloudflare by
  default; swap the provider in `templates/traefik-core-compose.yml`). Each
  the control host's Traefik fetches the apex cert (`<BASE_DOMAIN>` +
  `*.<BASE_DOMAIN>`); each zone's Forgejo router fetches `*.<slug>.<BASE_DOMAIN>`
  + `*.apps.<slug>.<BASE_DOMAIN>`.

Traefik terminates TLS everywhere, so no `insecure-registries` entry is needed.

## Standing up the event

```sh
# 1. Core services (Traefik + Watchtower) — once per node.
export BASE_DOMAIN=hackzone.com ACME_EMAIL=ops@hackzone.com CF_DNS_API_TOKEN=...
docker compose -f templates/traefik-core-compose.yml up -d

# 2. The control plane — once, on the control host. Front it with Traefik at
#    admin.hackzone.com / a tunnel; do not expose it raw.
export HZ_ADMIN_TOKEN=$(openssl rand -hex 32)      # keep this — it's the platform key
./hz control &

# 3. Register nodes.
./hz node add node-1 local              --cpus 32 --mem 128g
./hz node add node-2 ssh://ops@10.0.0.2 --cpus 32 --mem 128g --labels fast
./hz node list

# 4. Create zones (scheduler picks a node; --node to pin, --label to constrain).
BASE_DOMAIN=hackzone.com ./hz zone create alpha
BASE_DOMAIN=hackzone.com ./hz zone create bravo --node node-2
#   → Forgejo   https://git.alpha.hackzone.com/
#   → zone admin https://admin.alpha.hackzone.com/
#   → apps      https://<name>.apps.alpha.hackzone.com/   (one per app the team ships)
#   → state/zones/alpha/{zone-admin.txt, zone-token, deploy-token}
```

Hand each team lead their zone's **`zone-admin.txt`** (Forgejo admin login) and
**`zone-token`** (sign in at `admin.<slug>.hackzone.com`). They seed each repo they want
deployed with `.forgejo/workflows/deploy.yml` (from `examples/deploy.yml`) and
its repo variables/secrets — `REGISTRY`, `CONTROL_URL`, `APP_NAME`, `APP_PORT`,
`DEPLOY_TOKEN` (and an optional `FORGEJO_TOKEN`). A push to `main`, or the
zone admin hitting **Release** in the zone console (which auto-tags the next
`vX.Y.Z` on `main`), builds, pushes, and deploys
`APP_NAME.apps.<slug>.hackzone.com`; more
repos → more apps. The `POST /deploy`
rolls the app forward at once, and the per-node Watchtower keeps the deployed
`:<ref>` tag fresh afterwards (a re-push or base-image rebuild goes live on
its own, ~60s).

## During the event

```sh
./hz zone list                     # zones, node, footprint, app count
./hz zone status alpha             # forgejo + apps health on alpha's node
./hz app list                      # every deployed app across all zones (zone + name)
./hz app rm alpha shop             # stop and remove one app (zone, then name)
./hz zone move alpha --node node-1 # rebuild the zone elsewhere (data + apps don't follow)
./hz zone destroy alpha            # zone + every app it deployed
./hz node drain node-2             # stop placing new zones here
./hz sweep                         # reclaim idle zones now (or cron sweep-idle.sh)
```

`sweep-idle.sh` reads `state/zones/<slug>/last-activity`, bumped by
`hz zone create` and every deploy. Cron it (every ~15 min) so abandoned zones
free their node capacity automatically.

Teams deploy themselves — a team lead publishes a release in the Forgejo web
console (Repositories → pick a bump → **Release**, which auto-tags `main`) and
CI builds and ships it; a push to `main` works too. See
[Roles → Shipping a release](roles.md#shipping-a-release). You don't run
deploys for them.

## Capacity

Per-zone quotas have defaults at the top of `provision-zone.sh`:

| Var | Default | Notes |
|---|---|---|
| `CPU_FORGEJO` / `MEM_FORGEJO` | `1.0` / `1g` | idle Forgejo is light; bursts during Actions |
| `CPU_DIND` / `MEM_DIND` | `2.0` / `4g` | image builds are the heavy part |
| `CPU_RUNNER` / `MEM_RUNNER` | `1.0` / `2g` | |
| `CPU_APP` / `MEM_APP` | `1.0` / `1g` | one app container (counted once in the footprint; a zone may run more) |

The scheduler sums these limits as a zone's "footprint" for node accounting.
They are **limits**, not reservations — zones are bursty and rarely build at
the same instant, so nodes safely oversubscribe. As a rough guide, budget
~2–3 GB steady-state memory per active zone and size each node for the number
building concurrently, not the total. `hz node list` shows allocated vs.
capacity per node.

## Rough edges

- **DNS records for `git.<slug>` / `admin.<slug>` / `*.apps.<slug>` aren't
  created for you** — wildcard `*.<slug>.<BASE_DOMAIN>` for one node; automate
  per-record across nodes.
- **`traefik-public` is one flat network** — app containers and Forgejo
  instances on a node can reach each other by IP.
- **`hz-control` runs bare** with Docker access and every token on disk — it
  needs a systemd unit / locked-down container and its own TLS front.
- **The control plane and the per-node Watchtower pull images anonymously** —
  private packages need `docker login git.<slug>.<BASE_DOMAIN>` on the node
  (Watchtower reads `${DOCKER_CONFIG_DIR:-/root/.docker}/config.json`).
- **No repo seeding, no roster loop** — both are top items in `CLAUDE.md`.
