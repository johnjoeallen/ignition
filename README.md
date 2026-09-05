# Ignition

Per-team hackathon infrastructure. Many teams (target ~80), a pool of hosts
("nodes"). Each team gets a fully isolated stack that stands up in seconds and
tears down without residue.

An isolated stack per team isn't just a security measure. Even with root everywhere and no
restrictions, isolated disposable stacks are the model that ships the most
working software per event — failure containment, zero coordination tax, an
identical clean start, a real deploy surface, and teardown that's actually
finished. See [Why an isolated stack per team](https://johnjoeallen.github.io/ignition/#why-an-isolated-stack-per-team).

Ignition is **one Java service, `ignition-control`, deployed as a container**.
Every platform-admin and team-admin operation is in its web UI — there is no
CLI. See [`ignition-control/`](ignition-control/) and [`DESIGN.md`](DESIGN.md).

`BASE_DOMAIN` is the apex where Ignition is hosted (e.g. `ignition.example`).
Each team owns the whole `<slug>.ignition.example` subtree; the **one console**
sits on the bare apex — everyone signs in there, and what they see/do is by
role (platform admin, team admin, team member), not by hostname:

| host | what |
|---|---|
| `ignition.example` | the console (`ignition-control`) — a team's own view is `/teams/<slug>` on this same host |
| `git.<slug>.ignition.example` | that team's Forgejo — git, PRs, Actions, container registry |
| `<app>.apps.<slug>.ignition.example` | a deployed app — a team can run many; names are unique within the team |

`ignition.example` is a placeholder — set `BASE_DOMAIN` to any apex your
organisation controls. DNS is one pre-registered wildcard `*.<apex>` → the
**controller**, which matches at any depth (RFC 4592), so provisioning a team
adds no records. The controller is the only public machine and the only place
TLS terminates: it owns `:443`, runs the SSO gateway, and reverse-proxies by
`Host` over WireGuard to nodes on a private network with no inbound. Behind the
edge everything is plain HTTP. See
[Exposure & access](https://johnjoeallen.github.io/ignition/exposure/).

Per team: one Forgejo, a private Docker-in-Docker build engine so one team's CI
can never touch another's, and any number of live apps deployed on a release.

📖 **[Concept, executive overview, and architecture →
johnjoeallen.github.io/ignition](https://johnjoeallen.github.io/ignition/)**

Read **[CLAUDE.md](CLAUDE.md)** for the design decisions before changing anything.

## Roles

A signed-in user's role is a union: **platform admin** (global) plus, per
team, **team admin** or **team member** — so someone can be a platform admin
overall and also just a team admin of one specific team. All three sign in at
the same place, email + password (first run is `/setup`); what they see and
can do is entirely by role, not by which URL they hit.

- **Platform admin** — registers nodes, provisions / moves / destroys teams,
  runs the roster, sees every team and app, manages every Ignition account.
  Implicitly a team admin of every team too.
- **Team admin** — the team lead(s), one or more per team. Adds/removes team
  members and their roles, creates repos, cuts releases (automated semver
  bumps), manages the team's apps, restarts the runner — for *their* team
  only, at `/teams/<slug>` on the console. Git access isn't a separate step:
  adding someone as a member provisions their Forgejo login too, as their
  sanitized email. They never touch a Forgejo admin screen.
- **Team member** — the rest of the team. Same team console, everything but
  member management.

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
  `git.<slug>` / `*.apps.<slug>` name resolves with no per-team
  record.
- A DNS-provider API token for the edge's ACME DNS-01 challenge. The edge
  fetches `ignition.example` + `*.ignition.example` and, per team,
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
printf 'YOUR_PROVIDER_TOKEN=…\n' > acme.env       # DNS API creds for the ACME challenge
export IGN_SECRET_KEY=$(head -c32 /dev/urandom | base64)   # zone-secret AES key — keep it
export IGN_USER_SECRET_PEPPER=$(uuidgen)                    # per-user git secret key ingredient — keep it
export IGN_PUBLIC_URL=https://ignition.example
export POSTGRES_PASSWORD=$(openssl rand -hex 24)
export IGN_SMTP_HOST=… IGN_SMTP_USERNAME=… IGN_SMTP_PASSWORD=… IGN_SMTP_FROM='Ignition <ignition@ignition.example>'
docker compose --project-directory . -f templates/ignition-control-compose.yml up -d
```

First run logs a one-time setup code (`IGNITION SETUP …`). Open
**`https://ignition.example/setup`**, enter it plus an email and password
to create the platform admin. Then from the console:

- **Nodes → Register** — add each host (`local`, `ssh://ops@10.0.0.2`, or
  `tcp://host:2376`) with its CPU / memory.
- **Provision a team** — a slug; the scheduler places it, or pin a node. It
  stands up Forgejo + DinD + a runner, and makes you that team's admin. From
  **Users**, invite the team lead an Ignition account (if they don't have one
  yet); from the team's console, add them as a team admin — they take it from
  there.
- **Roster** — paste a slug list to bulk-provision or bulk-destroy an event.
- Per team: **move**, **destroy**; per app: **stop**; **sweep idle teams now**.

**Create app** in the team console seeds the repo with a starter `Dockerfile`
and `.forgejo/workflows/deploy.yml` ([`examples/deploy.yml`](examples/deploy.yml))
plus every var/secret that workflow needs — `REGISTRY`, `REGISTRY_USER`,
`CONTROL_URL`, `APP_NAME`, `APP_PORT`, `DEPLOY_TOKEN`, `FORGEJO_TOKEN` — so
there's nothing to wire up by hand.

Builds **start from a release** — in the team console under Repositories the
team admin clicks **Release**; `ignition-control` reads the commits since the
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
| `examples/deploy.yml` | sample CI workflow to seed into a team's repo |
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
- **No repo seeding** — the starter repo + repo vars/secrets are still set by
  hand per team.
- **No services catalogue yet** — an app's own infra (Postgres, Redis) belongs
  in its Dockerfile, but the shared services every team needs — standing mocks
  (a payments sandbox, a rewards engine, a signing service, an LLM gateway) and
  sandbox API proxies that hold the org's test keys so no app ever sees one —
  should run once on the controller and be offered to every team (`CLAUDE.md`,
  task 3).
