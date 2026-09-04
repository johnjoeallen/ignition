# Ignition

Per-team hackathon infrastructure. Many teams (target ~80), a pool of hosts
("nodes"). Each team gets a fully isolated stack (a "zone") that stands up in
seconds and tears down without residue.

A zone per team isn't just a security measure. Even with root everywhere and no
restrictions, isolated disposable stacks are the model that ships the most
working software per event — failure containment, zero coordination tax, an
identical clean start, a real deploy surface, and teardown that's actually
finished. See [Why a zone per team](https://johnjoeallen.github.io/ignition/#why-a-zone-per-team).

Ignition is **one Java service, `ignition-control`, deployed as a container**.
Every platform-admin and zone-admin operation is in its web UI — there is no
CLI. See [`ignition-control/`](ignition-control/) and [`DESIGN.md`](DESIGN.md).

`BASE_DOMAIN` is the apex where Ignition is hosted (e.g. `ignition.example`).
Each zone owns the whole `<slug>.ignition.example` subtree; the platform admin
sits on the apex:

| host | what |
|---|---|
| `admin.ignition.example` | the platform console (`ignition-control`) |
| `git.<slug>.ignition.example` | that zone's Forgejo — git, PRs, Actions, container registry |
| `admin.<slug>.ignition.example` | that zone's console (same service, zone-scoped) |
| `<app>.apps.<slug>.ignition.example` | a deployed app — a zone can run many; names are unique within the zone |

`ignition.example` is a placeholder — set `BASE_DOMAIN` to any apex your
organisation controls. DNS is one pre-registered wildcard `*.<apex>` → the
**controller**, which matches at any depth (RFC 4592), so provisioning a zone
adds no records. The controller is the only public machine and the only place
TLS terminates: it owns `:443`, runs the SSO gateway, and reverse-proxies by
`Host` over WireGuard to nodes on a private network with no inbound. Behind the
edge everything is plain HTTP. See
[Exposure & access](https://johnjoeallen.github.io/ignition/exposure/).

Per zone: one Forgejo, a private Docker-in-Docker build engine so one zone's CI
can never touch another's, and any number of live apps deployed on a release.

📖 **[Concept, executive overview, and architecture →
johnjoeallen.github.io/ignition](https://johnjoeallen.github.io/ignition/)**

Read **[CLAUDE.md](CLAUDE.md)** for the design decisions before changing anything.

## Roles

- **Platform admin** — registers nodes, provisions / moves / destroys zones,
  runs the roster, sees every zone and app. Works entirely in the **platform
  console** at `admin.ignition.example` (bearer = `IGN_ADMIN_TOKEN`).
- **Zone admin** — one per zone (the team lead). Adds users, creates repos,
  cuts releases (automated semver bumps), manages the zone's apps, restarts the
  runner — all from the **zone console** at `admin.<slug>.ignition.example`, for
  *their* zone only. They never touch a Forgejo admin screen.

**Forgejo, not Gitea:** community-governed (Codeberg e.V.), FOSS-first, no
open-core drift. Server `codeberg.org/forgejo/forgejo:11` (LTS), runner
`code.forgejo.org/forgejo/runner:13`.

## Prerequisites

- The **controller** — the one public machine — with Docker + Compose v2. It
  owns `:443`, terminates all TLS, runs the SSO gateway, and reaches each node
  over WireGuard.
- **Nodes**: hosts with Docker on a private network with **no inbound**;
  `docker network create traefik-public` and a WireGuard peer to the controller
  on each.
- **DNS**: one pre-registered wildcard `*.ignition.example` → the controller,
  set up once. It matches at any depth (RFC 4592), so every
  `git.<slug>` / `admin.<slug>` / `*.apps.<slug>` name resolves with no per-zone
  record.
- A DNS-provider API token for the edge's ACME DNS-01 challenge. The edge
  fetches `ignition.example` + `*.ignition.example` and, per zone,
  `*.<slug>.ignition.example` + `*.apps.<slug>.ignition.example` (cert wildcards
  are single-label, so these are two labels deep).

All TLS terminates at the controller's edge; behind it, over WireGuard,
everything is plain HTTP — no `insecure-registries` entry is needed.

## Quickstart

```sh
# 1. Core services (internal Traefik + Watchtower) — once per node, on the
#    private network. No public ports, no certs here.
docker network create traefik-public
docker volume  create ignition-dynamic        # shared with the control plane
docker compose --project-directory . -f templates/traefik-core-compose.yml up -d

# 2. The controller — once, the only public machine. Runs the edge (owns :443,
#    all TLS/ACME), the SSO gateway, and the control plane.
export BASE_DOMAIN=ignition.example ACME_EMAIL=ops@ignition.example ACME_DNS_PROVIDER=<your-dns>
printf 'YOUR_PROVIDER_TOKEN=…\n' > acme.env      # DNS API creds for the ACME challenge
export IGN_ADMIN_TOKEN=$(openssl rand -hex 32)   # the platform key — keep it
docker compose --project-directory . -f templates/ignition-control-compose.yml up -d
```

Then open **`https://admin.ignition.example/`**, sign in with `IGN_ADMIN_TOKEN`,
and from the console:

- **Nodes → Register** — add each host (`local`, `ssh://ops@10.0.0.2`, or
  `tcp://host:2376`) with its CPU / memory.
- **Provision a zone** — a slug; the scheduler places it, or pin a node. It
  stands up Forgejo + DinD + a runner and mints the zone-admin and CI tokens.
  Hand the team lead the **zone token** (their console sign-in) and the
  **deploy token** (`DEPLOY_TOKEN` repo secret).
- **Roster** — paste a slug list to bulk-provision or bulk-destroy an event.
- Per zone: **move**, **destroy**; per app: **stop**; **sweep idle zones now**.

A zone lead then adds `.forgejo/workflows/deploy.yml`
([`examples/deploy.yml`](examples/deploy.yml)) to a repo with its vars/secrets
(`REGISTRY`, `CONTROL_URL`, `APP_NAME`, `APP_PORT`, `DEPLOY_TOKEN`, and an
optional `FORGEJO_TOKEN`).

Builds **start from a release** — in the zone console under Repositories the
zone admin clicks **Release**; `ignition-control` reads the commits since the
last release, picks the bump (Conventional Commits: `fix:` → patch, `feat:` →
minor, `feat!:`/`BREAKING CHANGE:` → major; a dropdown overrides), and tags the
next `vX.Y.Z` on `main`. The tag builds, pushes to
`git.<slug>.ignition.example`, and deploys `APP_NAME.apps.<slug>.ignition.example`.
**A plain push to `main` does not deploy.** After a release, rollout is automatic
two ways: the workflow's `POST /deploy` rolls the app forward immediately, and
**`ignition-control` wires a Watchtower agent into every deployed app** so the
per-node Watchtower pulls a re-pushed digest on its own (~60s).

## Layout

| path | what |
|---|---|
| `ignition-control/` | the control plane — one Spring Boot service; both consoles, provisioning, scheduler, the CI `/deploy` bridge |
| `templates/ignition-control-compose.yml` | run the control plane on the controller |
| `templates/traefik-core-compose.yml` | per-node core: internal Traefik (`:80`, no certs) + Watchtower |
| `examples/deploy.yml` | sample CI workflow to seed into a zone's repo |
| `state/{nodes,zones,control}/` | generated — never hand-edit |
| `DESIGN.md` | the control-plane design |

The compose templates the service renders (`zone-compose.yml.tmpl`,
`app-compose.tmpl`) live in `ignition-control/src/main/resources/compose/`.

## Rough edges

- **The edge / SSO / WireGuard wiring in the compose templates is still being
  finished** — the architecture is the controller-only front door
  ([`docs/exposure.md`](docs/exposure.md)); `traefik-core-compose.yml` and
  `ignition-control-compose.yml` are catching up to it.
- **`traefik-public` is one flat network** on a node — app and Forgejo
  containers can reach each other by IP.
- **`ignition-control` holds every token**, is the single public front door,
  and drives every node's Docker daemon — a concentrated blast radius that
  needs a locked-down deployment.
- **The control plane and Watchtower pull images anonymously** — private
  packages need `docker login git.<slug>.ignition.example` on the node.
- **No repo seeding** — the starter repo + repo vars/secrets are still set by
  hand per zone.
- **No services catalogue yet** — an app's own infra (Postgres, Redis) belongs
  in its Dockerfile, but org-standard shared services (a card-art lookup, a
  rewards engine, a payments sandbox) should be one click to add to a zone,
  either as a blessed mock or a keyed proxy to the real thing (`CLAUDE.md`,
  task 5).
