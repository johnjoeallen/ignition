# hackzone-one

Per-team hackathon infrastructure. Up to ~80 teams on one host, each with a
fully isolated stack:

- **Forgejo** — git hosting, PRs/issues, Actions CI, and a container registry,
  all from one instance at `https://git.<slug>.<BASE_DOMAIN>/`
- **private DinD** — a per-team Docker-in-Docker engine so one team's CI can
  never touch another's images, containers or network
- **a live demo app** — `https://<slug>.<BASE_DOMAIN>/`, deployed by CI and
  routed by a single host-level Traefik (which also fronts the Forgejo above)

Teams are provisioned and torn down independently.

📖 **[Concept, executive overview, and architecture →
johnjoeallen.github.io/hackzone-one](https://johnjoeallen.github.io/hackzone-one/)**

Read **[CLAUDE.md](CLAUDE.md)** for the design decisions before changing anything.

**Forgejo, not Gitea.** Same feature set for our purposes (git, PRs, issues,
Actions, package/container registry), but Forgejo is under the non-profit
Codeberg e.V. with community governance and a FOSS-first, no-open-core
direction — the right footing for infrastructure we want to keep leaning on.
Server image `codeberg.org/forgejo/forgejo:11` (the LTS line), runner
`code.forgejo.org/forgejo/runner:13`.

## Prerequisites

- A host with Docker + Docker Compose v2, `envsubst` (gettext), `openssl`,
  Python 3.11+.
- DNS: `*.<BASE_DOMAIN>` → the host (covers the live apps). Each team's Forgejo
  is at `git.<slug>.<BASE_DOMAIN>` — two labels deep, so `*.<BASE_DOMAIN>`
  doesn't cover it. Add a `*.<slug>.<BASE_DOMAIN>` record per team, or one
  wildcard if your DNS allows it.
- A DNS-provider API token for Traefik's ACME DNS challenge — it fetches the
  `*.<BASE_DOMAIN>` cert and a `*.<slug>.<BASE_DOMAIN>` cert per team.

Traefik terminates TLS for both the apps and the Forgejo instances, so the
Docker daemon needs no `insecure-registries` entry.

## Quickstart

```sh
# 1. host Traefik (once)
export BASE_DOMAIN=hz.example.com ACME_EMAIL=ops@example.com CF_DNS_API_TOKEN=...
docker compose -f templates/traefik-core-compose.yml up -d

# 2. the deploy bridge (once) — front it with Traefik or a tunnel, not raw
./scripts/deploy-agent.py &

# 3. a team
BASE_DOMAIN=hz.example.com ./scripts/provision-team.sh team-a
#   -> Forgejo at https://git.team-a.hz.example.com/
#   -> live app  at https://team-a.hz.example.com/  (once CI deploys)
#   -> deploy token in state/team-a/deploy-token

# 4. tear it down
./scripts/teardown-team.sh team-a
```

Then, in the team's Forgejo: create the admin user, make a repo, add
`.forgejo/workflows/deploy.yml` (from [`examples/`](examples/deploy.yml)),
and set the repo vars/secrets it documents. A push to `main` builds, pushes to
the team registry, and calls the deploy agent.

## Layout

| path | what |
|---|---|
| `templates/traefik-core-compose.yml` | host Traefik, wildcard TLS |
| `templates/docker-compose.team.yml.tmpl` | per-team Forgejo + DinD + runner |
| `templates/app-compose.team.yml.tmpl` | per-team live app (applied by the deploy agent) |
| `scripts/provision-team.sh` | stand up one team |
| `scripts/teardown-team.sh` | tear down one team (`-p team-<slug> down -v`) |
| `scripts/sweep-idle.sh` | cron: reclaim teams idle past `IDLE_TTL` |
| `scripts/deploy-agent.py` | host HTTP service: CI → live app |
| `examples/deploy.yml` | sample CI workflow |
| `state/<slug>/` | generated per team — never hand-edit |

## Rough edges

- **Nothing creates the `git.<slug>.<BASE_DOMAIN>` DNS record.** Provisioning
  assumes it resolves already (a `*.<slug>.<BASE_DOMAIN>` record, or one per
  team). Wiring it to the DNS-provider API is the top next task.
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
