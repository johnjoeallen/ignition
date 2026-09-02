# hackzone-one

Per-team hackathon infrastructure. Up to ~80 teams on one host, each with a
fully isolated stack:

- **Gitea** — git hosting, PRs/issues, Actions CI, and a container registry,
  all from one instance on a dedicated port: `http://<slug>.<BASE_DOMAIN>:<port>/`
- **private DinD** — a per-team Docker-in-Docker engine so one team's CI can
  never touch another's images, containers or network
- **a live demo app** — `https://<slug>.<BASE_DOMAIN>/`, deployed by CI and
  routed by a single host-level Traefik

Teams are provisioned and torn down independently. Read **[CLAUDE.md](CLAUDE.md)**
for the design decisions before changing anything.

## Prerequisites

- A host with Docker + Docker Compose v2, `envsubst` (gettext), `openssl`,
  Python 3.11+.
- Wildcard DNS: `*.<BASE_DOMAIN>` → the host.
- A DNS-provider API token for the Traefik wildcard cert (Cloudflare by
  default).
- The Docker daemon must trust the per-team registries. They serve plain
  HTTP for now, so add them to `/etc/docker/daemon.json`
  `"insecure-registries"` (or put TLS in front — see rough edges) and
  `systemctl restart docker`.

## Quickstart

```sh
# 1. host Traefik (once)
export BASE_DOMAIN=hz.example.com ACME_EMAIL=ops@example.com CF_DNS_API_TOKEN=...
docker compose -f templates/traefik-core-compose.yml up -d

# 2. the deploy bridge (once) — front it with Traefik or a tunnel, not raw
./scripts/deploy-agent.py &

# 3. a team
BASE_DOMAIN=hz.example.com ./scripts/provision-team.sh team-a 0
#   -> Gitea at http://team-a.hz.example.com:30000/
#   -> deploy token in state/team-a/deploy-token

# 4. tear it down
./scripts/teardown-team.sh team-a
```

Then, in the team's Gitea: create the admin user, make a repo, add
`.gitea/workflows/deploy.yml` (from [`examples/`](examples/.gitea-workflows-deploy.yml)),
and set the repo vars/secrets it documents. A push to `main` builds, pushes to
the team registry, and calls the deploy agent.

## Layout

| path | what |
|---|---|
| `templates/traefik-core-compose.yml` | host Traefik, wildcard TLS |
| `templates/docker-compose.team.yml.tmpl` | per-team Gitea + DinD + runner |
| `templates/app-compose.team.yml.tmpl` | per-team live app (applied by the deploy agent) |
| `scripts/provision-team.sh` | stand up one team |
| `scripts/teardown-team.sh` | tear down one team (`-p team-<slug> down -v`) |
| `scripts/sweep-idle.sh` | cron: reclaim teams idle past `IDLE_TTL` |
| `scripts/deploy-agent.py` | host HTTP service: CI → live app |
| `examples/.gitea-workflows-deploy.yml` | sample CI workflow |
| `state/<slug>/` | generated per team — never hand-edit |

## Rough edges

- **Per-team Gitea ports are plain HTTP.** Fine on a trusted LAN; on open
  wifi put a TLS layer in front (per-port proxy, or a Traefik TCP router per
  port).
- **The deploy agent has no registry credentials.** It runs `docker pull`
  against the team registry as whatever the host daemon can reach — anonymous
  pulls of public packages work, private ones need `docker login` / a
  per-team `DOCKER_CONFIG` wired in.
- **No automatic repo seeding.** The starter repo, workflow file and repo
  vars/secrets are still set up by hand per team.
- **`deploy-agent.py` runs bare** with docker-socket access. Move it into a
  systemd unit or a locked-down container.
- **No roster loop.** Provisioning 80 teams is 80 script invocations; a
  `provision-all.sh` reading a roster file is a good next task.
