# Operating an Event

Everything is in the **one console**, at `https://<BASE_DOMAIN>/` — every
role (platform admin, team admin, team member) signs in there; what you see
and can do is by role, not by which URL you hit. There is no CLI.

## Prerequisites

- The **controller** — the one public machine — with Docker + Compose v2. It
  owns `:443`, terminates all TLS, runs the SSO gateway, and reaches each node
  over WireGuard.
- **Nodes** — hosts with Docker on a private network with **no inbound**, each
  with `docker network create traefik-public` and `docker volume create
  ignition-dynamic` (the shared Traefik-config volume), and a WireGuard peer to
  the controller.
- **DNS** (`BASE_DOMAIN` is the apex, e.g. `ignition.example`): one
  pre-registered wildcard `*.<BASE_DOMAIN>` → the controller, set up once and
  never touched again. It matches at any depth (RFC 4592), so
  `<BASE_DOMAIN>` itself, `git.<slug>.<BASE_DOMAIN>`, and
  `<app>.apps.<slug>.<BASE_DOMAIN>` all resolve with no per-team record.
  Provisioning a team adds zero DNS.
- API credentials for **your** DNS provider so the edge can answer the ACME
  DNS-01 challenge (`ACME_DNS_PROVIDER` + the matching vars in `acme.env` —
  any of Traefik's ~100 providers, including `rfc2136` for a self-run DNS).
  Set `ACME_CA_SERVER` to use a self-hosted `step-ca` instead of Let's Encrypt.
  The edge fetches the apex cert (`<BASE_DOMAIN>` + `*.<BASE_DOMAIN>`) and, per
  team, `*.<slug>.<BASE_DOMAIN>` + `*.apps.<slug>.<BASE_DOMAIN>`.

All TLS terminates at the controller's edge; behind it, over WireGuard,
everything is plain HTTP. See **[Exposure & access](exposure.md)** for the full
picture.

## Standing up the event

```sh
# 1. Core services (internal Traefik + Watchtower) — once per node,
#    on the private network. No public ports, no certs here.
docker network create traefik-public
docker volume  create ignition-dynamic
docker compose --project-directory . -f templates/traefik-core-compose.yml up -d

# 2. The controller — once, the only public machine. Runs the edge
#    (owns :443, all TLS/ACME), the SSO gateway, and the control plane.
export BASE_DOMAIN=ignition.example ACME_EMAIL=ops@ignition.example ACME_DNS_PROVIDER=<your-dns>
printf 'YOUR_PROVIDER_TOKEN=…\n' > acme.env          # DNS API creds for the ACME challenge
export IGN_SECRET_KEY=$(head -c32 /dev/urandom | base64)   # zone-secret AES key — keep it
export IGN_PUBLIC_URL=https://ignition.example
export POSTGRES_PASSWORD=$(openssl rand -hex 24)
export IGN_SMTP_HOST=… IGN_SMTP_USERNAME=… IGN_SMTP_PASSWORD=… IGN_SMTP_FROM='Ignition <ignition@ignition.example>'
docker compose --project-directory . -f templates/ignition-control-compose.yml up -d
```

First run logs a setup code (`docker compose … logs ignition-control | grep "IGNITION SETUP"`).
Open `https://ignition.example/setup`, enter it plus an email and
password to create the platform admin. Then sign in and:

3. **Nodes → Register** each host — `local`, `ssh://ops@10.0.0.2`, or
   `tcp://host:2376`, with its CPU / memory and any labels.
4. **Provision a team** — a slug per team. The scheduler places it on the
   least-loaded node that fits (or pin a node / require a label). It stands up
   Forgejo + DinD + a runner, creates the `ignition-bot` account, and mints the
   tokens. For a whole roster at once use **Roster** and paste the slug list.

Provisioning makes you that team's admin. From **Users**, invite the team
lead an Ignition account if they don't have one yet; from the team's console
(`/teams/<slug>` on the same host — no separate URL), add them as a team admin.
They take it from there — same console, scoped to their team by role, not by
a token. (`zone-admin.txt` is `ignition-control`'s own Forgejo service
credential; it never leaves the controller, and nobody signs in as it.)

In each repo they want deployed they add `.forgejo/workflows/deploy.yml` (from
`examples/deploy.yml`) with its variables/secrets — `REGISTRY`, `CONTROL_URL`,
`APP_NAME`, `APP_PORT`, `DEPLOY_TOKEN` (and an optional `FORGEJO_TOKEN`). The
team admin hits **Release** in the console — which reads the commits since the
last release, picks the bump, and tags the next `vX.Y.Z` on `main` — and that
tag builds, pushes, and deploys `APP_NAME.apps.<slug>.ignition.example`.
**A plain push to `main` does not deploy.** More repos → more apps.

## During the event

Everything is in the console:

- **Teams** — status, per-team **move** (rebuilds the stack on another node;
  the Forgejo volume doesn't follow) and **destroy** (team + every app).
- **Apps** — every deployed app; **stop** any.
- **Nodes → Drain** — stop placing new teams on a host.
- **Roster** — bulk provision / teardown, and **Sweep idle teams now**.

The idle sweep also runs on a timer (`ignition.sweep.interval`, default 15 min):
it reclaims any team whose `last-activity` — bumped by provisioning and every
deploy — is older than `ignition.sweep.ttl` (default 24 h). Set
`ignition.sweep.dry-run=true` to only report.

Teams deploy themselves — the team lead hits **Release** in the team console.
You don't run deploys for them. See
[Roles → Shipping a release](roles.md#shipping-a-release).

## Capacity

Per-team quotas are `ignition.quotas.*` (env-overridable):

| Property | Default | Notes |
|---|---|---|
| `cpu-forgejo` / `mem-forgejo` | `1.0` / `1g` | idle Forgejo is light; bursts during Actions |
| `cpu-dind` / `mem-dind` | `2.0` / `4g` | image builds are the heavy part |
| `cpu-runner` / `mem-runner` | `1.0` / `2g` | |
| `cpu-app` / `mem-app` | `1.0` / `1g` | one app container (counted once in the footprint; a team may run more) |

The scheduler sums these limits as a team's "footprint" for node accounting.
They are **limits**, not reservations — teams are bursty and rarely build at
the same instant, so nodes safely oversubscribe. As a rough guide, budget
~2–3 GB steady-state memory per active team and size each node for the number
building concurrently, not the total. The **Nodes** table shows allocated vs.
capacity per node.

## Rough edges

- **The edge / SSO / WireGuard wiring in the compose templates is still being
  finished** — the architecture is the controller-only front door
  ([Exposure & access](exposure.md)); `traefik-core-compose.yml` and
  `ignition-control-compose.yml` are catching up to it.
- **`traefik-public` is one flat network** — app containers and Forgejo
  instances on a node can reach each other by IP.
- **`ignition-control` holds every token**, is the single public front door,
  and drives every node's Docker daemon — the controller is a
  concentrated blast radius and needs a locked-down deployment.
- **The control plane and the per-node Watchtower pull images anonymously** —
  private packages need `docker login git.<slug>.<BASE_DOMAIN>` on the node.
- **No repo seeding** — the starter repo + repo vars/secrets are still set by
  hand per team.
- **No services catalogue** — org-standard shared services (a card-art lookup,
  a rewards engine, a payments sandbox), as a blessed mock or a keyed proxy to
  the real thing, are a planned one-click add-on for a team (`CLAUDE.md`,
  task 3). An app's own infra (Postgres, Redis) stays in its Dockerfile.
