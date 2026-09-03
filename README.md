# Ignition

Per-team hackathon infrastructure. Many teams (target ~80), a pool of hosts
("nodes"). Each team gets a fully isolated stack (a "zone") that stands up in
seconds and tears down without residue.

A zone per team isn't just a security measure. Even with root everywhere and no
restrictions, isolated disposable stacks are the model that ships the most
working software per event — failure containment, zero coordination tax, an
identical clean start, a real deploy surface, and teardown that's actually
finished. See [Why a zone per team](https://johnjoeallen.github.io/ignition/#why-a-zone-per-team).

`BASE_DOMAIN` is the apex where ignition is hosted (e.g. `ignition.example`). Each zone
owns the whole `<slug>.ignition.example` subtree; the platform admin sits on the apex:

| host | what |
|---|---|
| `admin.ignition.example` | the platform admin control plane (`ign-control`) |
| `git.<slug>.ignition.example` | that zone's Forgejo — git, PRs, Actions, container registry |
| `admin.<slug>.ignition.example` | that zone's admin view (same `ign-control`, zone-scoped) |
| `<app>.apps.<slug>.ignition.example` | a deployed app — a zone can run many; names are unique within the zone |

`ignition.example` is a placeholder — set `BASE_DOMAIN` to any apex your
organisation controls, as long as its DNS can serve records two labels deep
(`git.<slug>.<apex>`) and you can get wildcard certs for `*.<slug>.<apex>`.

Per zone: one Forgejo, a private Docker-in-Docker build engine so one zone's CI
can never touch another's, and any number of live apps that its CI deploys on
every push.

📖 **[Concept, executive overview, and architecture →
johnjoeallen.github.io/ignition](https://johnjoeallen.github.io/ignition/)**

Read **[CLAUDE.md](CLAUDE.md)** for the design decisions before changing anything.

## Roles

- **Platform admin** — registers nodes, creates/places/moves/destroys zones,
  sees every zone and app. Uses the `ign` CLI and `admin.ignition.example`.
- **Zone admin** — one per zone (the team lead). Adds users, creates repos,
  cuts releases (automated semver bumps), manages the zone's apps, restarts the
  runner — all from the **zone console** at `admin.<slug>.ignition.example`, for
  *their* zone only. They never touch a Forgejo admin screen.

**Forgejo, not Gitea:** community-governed (Codeberg e.V.), FOSS-first, no
open-core drift. Server `codeberg.org/forgejo/forgejo:11` (LTS), runner
`code.forgejo.org/forgejo/runner:13`.

## Prerequisites

- A control host with Docker + Docker Compose v2, `envsubst` (gettext),
  `openssl`, `bash`, Python 3.11+, and a way to reach each node's Docker
  daemon (local socket, `ssh://`, or `tcp://`+TLS).
- Nodes: hosts with Docker; `docker network create traefik-public` on each.
- DNS: `git.<slug>.ignition.example` and `*.apps.<slug>.ignition.example` must resolve to the node
  running that zone; `admin.<slug>.ignition.example` to the control host. A single
  `*.<slug>.ignition.example` A-record covers all three when the zone shares the control
  host; otherwise split it. `admin.ignition.example` → the control host.
- A DNS-provider API token for Traefik's ACME DNS challenge. The control host's
  Traefik fetches the apex cert (`ignition.example` + `*.ignition.example`); each zone's Forgejo
  router fetches `*.<slug>.ignition.example` + `*.apps.<slug>.ignition.example` (two labels deep, so
  the apex wildcard misses them).

Traefik terminates TLS everywhere, so no `insecure-registries` entry is needed.

## Quickstart

```sh
# 1. Core services (Traefik + Watchtower) — once per node.
export BASE_DOMAIN=ignition.example ACME_EMAIL=ops@ignition.example CF_DNS_API_TOKEN=...
docker compose -f templates/traefik-core-compose.yml up -d

# 2. The control plane, once, on the control host — its Traefik fronts it at
#    admin.ignition.example and admin.<slug>.ignition.example via state/control/dynamic/.
IGN_ADMIN_TOKEN=$(openssl rand -hex 32) BASE_DOMAIN=ignition.example ./ign control &

# 3. Register nodes.
./ign node add node-1 local              --cpus 32 --mem 128g
./ign node add node-2 ssh://ops@10.0.0.2 --cpus 32 --mem 128g

# 4. Create a zone (scheduler picks a node; --node to pin, --label to constrain).
BASE_DOMAIN=ignition.example ./ign zone create quantum-badgers
#   -> Forgejo     https://git.quantum-badgers.ignition.example/
#   -> zone admin  https://admin.quantum-badgers.ignition.example/
#   -> apps        https://<name>.apps.quantum-badgers.ignition.example/   (CI deploys each app on a release)
#   -> state/zones/quantum-badgers/{zone-admin.txt, zone-token, deploy-token}

./ign zone list
./ign zone status quantum-badgers
./ign app list                 # every deployed app across all zones
./ign zone destroy quantum-badgers       # zone + all its apps
```

A zone lead then adds `.forgejo/workflows/deploy.yml`
([`examples/deploy.yml`](examples/deploy.yml)) to a repo with its vars/secrets
(`REGISTRY`, `CONTROL_URL`, `APP_NAME`, `APP_PORT`, `DEPLOY_TOKEN`, and an
optional `FORGEJO_TOKEN`).

Builds **start from a release** — in the zone console under Repositories the
zone admin clicks **Release**; ign-control reads the commit messages since the
last release, picks the bump from them (Conventional Commits: `fix:` → patch,
`feat:` → minor, `feat!:`/`BREAKING CHANGE:` → major; a dropdown overrides),
and tags the next `vX.Y.Z` on `main` — no one bumps a version, no
`git push --tags`. The tag builds, pushes to `git.quantum-badgers.ignition.example`, and
deploys `APP_NAME.apps.quantum-badgers.ignition.example`. **A plain push to `main` does not
deploy** — only a release does. Several repos → several apps. After a release,
rollout is automatic two ways: the workflow's `POST /deploy` rolls the app
forward immediately, and
**ign-control wires a Watchtower agent into every deployed app** (the
`app-compose.tmpl` label is added for you) so the per-node Watchtower pulls a
new digest for that tag on its own (~60s poll) — a re-push or a base-image
rebuild goes live without another workflow run.

## Layout

| path | what |
|---|---|
| `ign` | platform CLI: `ign node \| zone \| app \| sweep \| control` |
| `control/ign-control.py` | control plane — platform view, zone-admin surface, CI `/deploy` + `/undeploy` |
| `templates/traefik-core-compose.yml` | per-node core: Traefik (apex cert + file provider for `admin.*`) + Watchtower (auto-rolls deployed apps) |
| `templates/zone-compose.yml.tmpl` | per-zone Forgejo + DinD + runner |
| `templates/app-compose.tmpl` | one deployed app |
| `scripts/{node,zone,app,scheduler,provision-zone,teardown-zone,sweep-idle}.sh` | the CLI internals |
| `examples/deploy.yml` | sample CI workflow |
| `state/{nodes,zones,control}/` | generated — never hand-edit |

## Rough edges

- **DNS records for `git.<slug>` / `admin.<slug>` / `*.apps.<slug>` aren't
  created for you** — wildcard `*.<slug>.ignition.example` for one node; automate
  per-record across nodes.
- **`traefik-public` is one flat network** on a node — app and Forgejo
  containers can reach each other by IP.
- **`ign-control` runs bare** with Docker access and every token on disk — it
  needs a systemd unit / locked-down container behind `admin.ignition.example`.
- **The control plane and Watchtower pull images anonymously** — private
  packages need `docker login git.<slug>.ignition.example` on the node (Watchtower
  reads `${DOCKER_CONFIG_DIR:-/root/.docker}/config.json`).
- **No repo seeding, no roster loop** — both are top next tasks (`CLAUDE.md`).
