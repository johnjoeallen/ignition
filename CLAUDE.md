# CLAUDE.md

Context for Claude Code when working in this repo. Read this before making
changes — several design decisions here look "wrong" in isolation but were
made deliberately to work around real constraints. See the "Decisions and
why" section before refactoring any of them.

## What this is

Infrastructure for a private, short-lived, per-team hackathon environment.
Up to 80 teams, one core host. Each team gets a fully isolated stack:

- **git hosting + PRs/issues + CI + container registry** — all via a single
  Forgejo instance per team (Forgejo natively does all four; no separate
  GitLab/Woodpecker/registry:2 needed)
- **isolated build sandbox** — a per-team `docker:dind` sidecar, so one
  team's CI can never see another team's containers/images/network
- **a live deployed demo app** — reachable at `https://<team-slug>.<BASE_DOMAIN>/`

Stacks are stood up and torn down independently per team, not as one big
shared deployment.

## Repo layout

```
templates/
  docker-compose.team.yml.tmpl   # per-team forgejo + dind + runner (envsubst template)
  app-compose.team.yml.tmpl      # per-team live demo app (envsubst template)
  traefik-core-compose.yml       # host-level Traefik, wildcard TLS, runs once
scripts/
  provision-team.sh              # stand up one team's full stack
  teardown-team.sh               # tear down one team's full stack
  sweep-idle.sh                  # cron: reclaim idle team stacks past a TTL
  deploy-agent.py                # host-level HTTP service: CI -> live app bridge
examples/
  deploy.yml    # sample workflow to seed into each team's starter repo
state/                           # generated at runtime, one dir per team (gitignored)
README.md                        # operator-facing overview
```

## Decisions and why (don't relitigate these without reading first)

**Forgejo gets a dedicated host port per team, not a subdomain path.**
Docker always hits `/v2/...` at domain root for registry operations — it
ignores path prefixes. Putting Forgejo's web UI at `team-x.domain/git` would
silently break `docker push`/`pull` against that same Forgejo's built-in
registry. Fix: Forgejo owns `team-x.<BASE_DOMAIN>:<port>` entirely (UI, git
clone, Actions, registry all on one origin), and the clean root domain on
443 is reserved for the live app via Traefik.

**The live demo app is deployed from the host, not from inside the team's
DinD sandbox.** A container built and run inside a nested Docker-in-Docker
engine lives in that engine's own private network namespace — Traefik on
the host has no route to it. So CI pushes an image, then calls
`deploy-agent.py` (running on the host, bearer-token authed per team) to
actually run the container on the shared `traefik-public` network where
Traefik can see it. This mirrors any CI→orchestrator handoff (e.g. GitLab
CI calling out to k8s): build sandbox stays isolated, serving layer
doesn't.

**Ports are allocated as `30000 + team_index`.** Deterministic and
idempotent — re-running `provision-team.sh` for the same team/index doesn't
collide or drift. If you change this, `deploy-agent.py`'s assumptions about
one-token-per-team still hold, but anything that hardcodes the port math
elsewhere needs updating too.

**Runner registration is a two-phase compose apply.** Forgejo uses a
40-hex-char *shared secret* we generate ourselves (`openssl rand -hex 20`),
so we could in principle know the runner's UUID up front — but the secret
still has to be registered *against Forgejo's database*
(`forgejo forgejo-cli actions register --secret ...`), which only exists
once Forgejo has done its first-run init. So `provision-team.sh` starts
`forgejo`+`dind`, waits for health, runs the register via `docker exec`,
writes `state/<slug>/runner-config.yml` (url + derived UUID + secret), then
starts the `runner`. Don't collapse this into one `up -d`.

The runner's UUID is derived deterministically from the secret — the first
16 chars, as raw bytes, hex-encoded, formatted `8-4-4-4-12` (see
`forgejo_uuid` in `scripts/lib.sh`). That's how Forgejo itself computes it,
so we don't need to parse the register command's stdout.

## Conventions

- All per-team Docker resources are prefixed `team-<slug>` (containers,
  networks, volumes, compose project name) so `docker compose -p
  team-<slug> down -v` is a complete, safe teardown.
- Templates are rendered with `envsubst`, not a templating engine — keep
  them shell-simple. If a template needs conditionals/loops, that's a sign
  it should become a script that generates compose fragments instead.
- Per-team resource limits are set as env vars with defaults at the top of
  `provision-team.sh` (`CPU_FORGEJO`, `MEM_DIND`, etc.) — change quotas there,
  not by hand-editing rendered output in `state/`.
- `state/` is generated, not source — never hand-edit files under it;
  re-run `provision-team.sh` instead so the rendering stays reproducible.

## Known gaps (see README "rough edges" for full list)

- Forgejo's per-team port currently serves plain HTTP. Fine on a trusted LAN;
  needs a TLS-terminating layer (per-port proxy or Traefik TCP router)
  before use on open wifi.
- `deploy-agent.py` needs `docker login` credentials for each team's Forgejo
  registry available on the host to pull images — not yet wired up.
- No automated repo-seeding step yet: `examples/deploy.yml`
  and the repo vars/secrets it expects (`FORGEJO_TOKEN`, `DEPLOY_TOKEN`, etc.)
  currently have to be pushed into each team's starter repo by hand as part
  of `provision-team.sh` — a good next task.

## Likely next tasks

1. Extend `provision-team.sh` to create the team's starter repo via Forgejo's
   API and push `examples/deploy.yml` + repo vars/secrets
   automatically.
2. Wire `deploy-agent.py` into a systemd unit or its own locked-down
   container (it currently assumes being run bare with docker.sock access).
3. Add a `provision-all.sh` / `teardown-all.sh` that reads a team roster
   file and loops the per-team scripts, for one-shot event start/end.
4. Decide on and implement TLS for the per-team Forgejo ports before running
   this on anything but a trusted network
