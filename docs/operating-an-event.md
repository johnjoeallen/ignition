# Operating an Event

This is the current shell workflow. The [in-progress rewrite](architecture.md)
replaces every `ign …` command here with a button in the platform console.

## Prerequisites

- A **control host** with Docker + Compose v2, `envsubst` (gettext),
  `openssl`, `bash`, Python 3.11+, and a way to reach each node's Docker
  daemon (local socket, `ssh://`, or `tcp://`+TLS).
- **Nodes** — hosts with Docker, each with the shared network created:
  `docker network create traefik-public`.
- **DNS** (`BASE_DOMAIN` is the apex, e.g. `ignition.example`):
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
export BASE_DOMAIN=ignition.example ACME_EMAIL=ops@ignition.example CF_DNS_API_TOKEN=...
docker compose -f templates/traefik-core-compose.yml up -d

# 2. The control plane — once, on the control host. Front it with Traefik at
#    admin.ignition.example / a tunnel; do not expose it raw.
export IGN_ADMIN_TOKEN=$(openssl rand -hex 32)      # keep this — it's the platform key
./ign control &

# 3. Register nodes.
./ign node add node-1 local              --cpus 32 --mem 128g
./ign node add node-2 ssh://ops@10.0.0.2 --cpus 32 --mem 128g --labels fast
./ign node list

# 4. Create zones (scheduler picks a node; --node to pin, --label to constrain).
BASE_DOMAIN=ignition.example ./ign zone create quantum-badgers
BASE_DOMAIN=ignition.example ./ign zone create pixel-foxes --node node-2
#   → Forgejo   https://git.quantum-badgers.ignition.example/
#   → zone admin https://admin.quantum-badgers.ignition.example/
#   → apps      https://<name>.apps.quantum-badgers.ignition.example/   (one per app the team ships)
#   → state/zones/quantum-badgers/{zone-admin.txt, zone-token, deploy-token}
```

Hand each team lead just their zone's **`zone-token`** — they sign in with it at
`https://admin.<slug>.ignition.example/`, the zone console, and that's their whole
surface (`zone-admin.txt` is ign-control's service credential; keep it on the
control host). In each repo they want deployed they add
`.forgejo/workflows/deploy.yml` (from `examples/deploy.yml`) and its
variables/secrets — `REGISTRY`, `CONTROL_URL`, `APP_NAME`, `APP_PORT`,
`DEPLOY_TOKEN` (and an optional `FORGEJO_TOKEN`). The zone admin hits
**Release** in the console — which reads the commits since the last release,
picks the bump, and tags the next `vX.Y.Z` on `main` — and that tag builds,
pushes, and deploys `APP_NAME.apps.<slug>.ignition.example`. **A plain push to
`main` does not deploy.** More repos → more apps. The `POST /deploy` rolls the
app forward at once; if CI later re-runs for the same tag, the per-node
Watchtower picks up the new image (~60s).

## During the event

```sh
./ign zone list                     # zones, node, footprint, app count
./ign zone status quantum-badgers             # forgejo + apps health on quantum-badgers's node
./ign app list                      # every deployed app across all zones (zone + name)
./ign app rm quantum-badgers paywise             # stop and remove one app (zone, then name)
./ign zone move quantum-badgers --node node-1 # rebuild the zone elsewhere (data + apps don't follow)
./ign zone destroy quantum-badgers            # zone + every app it deployed
./ign node drain node-2             # stop placing new zones here
./ign sweep                         # reclaim idle zones now (or cron sweep-idle.sh)
```

`sweep-idle.sh` reads `state/zones/<slug>/last-activity`, bumped by
`ign zone create` and every deploy. Cron it (every ~15 min) so abandoned zones
free their node capacity automatically.

Teams deploy themselves — in the zone console the team lead hits **Release**
(Repositories); ign-control derives the version bump from the commits since the
last release, tags the next `vX.Y.Z` on `main`, and CI builds and ships it. A
plain push to `main` does not deploy. See
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
building concurrently, not the total. `ign node list` shows allocated vs.
capacity per node.

## Rough edges

- **DNS records for `git.<slug>` / `admin.<slug>` / `*.apps.<slug>` aren't
  created for you** — wildcard `*.<slug>.<BASE_DOMAIN>` for one node; automate
  per-record across nodes.
- **`traefik-public` is one flat network** — app containers and Forgejo
  instances on a node can reach each other by IP.
- **`ign-control` runs bare** with Docker access and every token on disk — it
  needs a systemd unit / locked-down container and its own TLS front.
- **The control plane and the per-node Watchtower pull images anonymously** —
  private packages need `docker login git.<slug>.<BASE_DOMAIN>` on the node
  (Watchtower reads `${DOCKER_CONFIG_DIR:-/root/.docker}/config.json`).
- **No repo seeding, no roster loop** — both are top items in `CLAUDE.md`.
- **No services catalogue** — org-standard shared services (a card-art lookup,
  a rewards engine, a payments sandbox), as a blessed mock or a keyed proxy to
  the real thing, are a planned one-click add-on for a zone (`CLAUDE.md`,
  task 5). An app's own infra (Postgres, Redis) stays in its Dockerfile.
