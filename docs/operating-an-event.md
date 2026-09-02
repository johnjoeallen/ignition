# Operating an Event

## Prerequisites

- A host with Docker + Docker Compose v2, `envsubst` (gettext), `openssl`,
  Python 3.11+.
- DNS: `*.<event-domain>` → the host (covers every team's live app). Each
  team's Forgejo is at `git.<slug>.<event-domain>` — two labels deep, so
  `*.<event-domain>` misses it; add a `*.<slug>.<event-domain>` record per team
  (or one broad record if your DNS allows it).
- A DNS-provider API token for Traefik's ACME DNS challenge (Cloudflare by
  default; swap the provider in `templates/traefik-core-compose.yml`). Traefik
  fetches the `*.<event-domain>` cert plus a `*.<slug>.<event-domain>` cert per
  team.

Traefik terminates TLS for both the apps and the Forgejo instances with real
certificates, so the host Docker daemon needs **no `insecure-registries`
entry**.

## Standing up the event

```sh
# 1. Host Traefik — once, before any team.
export BASE_DOMAIN=hz.example.com ACME_EMAIL=ops@example.com CF_DNS_API_TOKEN=...
docker compose -f templates/traefik-core-compose.yml up -d

# 2. The deploy bridge — once. Front it with Traefik / a tunnel; do not expose it raw.
./scripts/deploy-agent.py &

# 3. A team.
BASE_DOMAIN=hz.example.com ./scripts/provision-team.sh team-a
#   → Forgejo at https://git.team-a.hz.example.com/
#   → live app  at https://team-a.hz.example.com/  (once CI deploys)
#   → deploy token at state/team-a/deploy-token
```

Then, in the team's Forgejo: create the admin user, make a repo, add
`.forgejo/workflows/deploy.yml` (from `examples/deploy.yml`), and set the repo
variables and secrets it documents (`REGISTRY`, `DEPLOY_URL`, `APP_PORT`,
`FORGEJO_TOKEN`, `DEPLOY_TOKEN`). A push to `main` then builds, pushes, and
deploys.

## During the event

```sh
./scripts/teardown-team.sh team-a          # complete: containers, volumes, state
IDLE_TTL=86400 ./scripts/sweep-idle.sh     # cron: reclaim stacks idle > TTL
```

`sweep-idle.sh` reads `state/<slug>/last-activity`, which `provision-team.sh`
and every deploy bump. Run it from cron (e.g. every 15 minutes) so abandoned
stacks free their quota automatically.

## Capacity

Per-team quotas have defaults at the top of `provision-team.sh`:

| Var | Default | Notes |
|---|---|---|
| `CPU_FORGEJO` / `MEM_FORGEJO` | `1.0` / `1g` | idle Forgejo is light; bursts during Actions |
| `CPU_DIND` / `MEM_DIND` | `2.0` / `4g` | image builds are the heavy part |
| `CPU_RUNNER` / `MEM_RUNNER` | `1.0` / `2g` | |
| `CPU_APP` / `MEM_APP` | `1.0` / `1g` | the live demo container |

These are **limits**, not reservations — teams are bursty and rarely build at
the same instant, so a host can safely oversubscribe. As a rough guide, budget
~2–3 GB of steady-state memory per active team and size the box for the
number you expect building concurrently, not the total team count. Tighten the
DinD quota first if you need to fit more.

## Rough edges

- **`git.<slug>.<event-domain>` DNS isn't created for you.** Provisioning
  assumes it already resolves (a `*.<slug>.<event-domain>` record, or one per
  team). Wiring it to the DNS-provider API is the top item in `CLAUDE.md`.
- **`traefik-public` is one flat network.** The live-app containers and the
  Forgejo instances can reach each other by IP; a Traefik-per-team network or an
  L3 policy would close that seam.
- **The deploy agent has no registry credentials.** It pulls as whatever the
  host daemon can reach — anonymous pulls of public packages work; private ones
  need `docker login` / a per-team `DOCKER_CONFIG` wired in.
- **No automatic repo seeding.** The starter repo, workflow file, and repo
  vars/secrets are still set up by hand per team. Automating this via Forgejo's
  API is the top item in `CLAUDE.md`.
- **`deploy-agent.py` runs bare** with docker-socket access — move it into a
  systemd unit or a locked-down container.
- **No roster loop.** Eighty teams is eighty script invocations; a
  `provision-all.sh` reading a roster file is a good next task.
