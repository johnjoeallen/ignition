# Demo, part 1 — Ignition on one LAN box (`spitfire`)

Get the whole Ignition stack running and validated on a **single machine on your
LAN**, reachable only from that LAN. No public exposure yet — that's
[part 2](demo-2-remote-access.md).

This part assumes you have never run Ignition, so it explains each moving part.

- **`spitfire`** — a box on your LAN, behind NAT, no inbound from the internet.
  It will run the *entire* stack: the edge Traefik (TLS), `ignition-control`
  (the control plane + web consoles), and later every team's Forgejo, build
  engine, runner, and deployed apps.

The apex used throughout is **`ignition.classesarecode.net`**.

```
     ┌──────────────────────────────┐
     │  spitfire        LAN only     │
     │  ── Traefik (edge) ──         │  owns :80/:443, all TLS via ACME DNS-01
     │  ── ignition-control ──       │  platform + zone web consoles
     │  (zones / apps come in part 2)│
     └──────────────────────────────┘
              ▲
              │  you test from a laptop on the same LAN,
              │  using an /etc/hosts override
```

> **Generate the config instead of copy-pasting.** The `demo/` directory has a
> generator for every file in this guide *and* part 2. Read on for what each
> value means, then:
> ```sh
> git clone https://github.com/johnjoeallen/ignition.git
> cd ignition/demo
> ./gen-templates.sh
> cp demo.conf.example demo.conf   # fill it in
> ./render.sh demo.conf            # -> demo/out/ + demo/out/INSTALL.txt
> ```
> It generates `IGN_SECRET_KEY` / `POSTGRES_PASSWORD` and the WireGuard keys for
> you. See [`demo/README.md`](demo/README.md).

---

## The pieces, explained

Read once; the steps refer back to this.

### The DNS-01 credential (and why that's all you need here)

"Let's Encrypt" issues the free TLS certificates that give a browser its
padlock. To prove you control `ignition.classesarecode.net`, it asks for a
temporary `TXT` record at `_acme-challenge.ignition.classesarecode.net`. Traefik
(via its built-in ACME client) creates and deletes that record automatically
**if you give it a credential for wherever that name's DNS is served**.

This is the **DNS-01** challenge. Its key property: the Certificate Authority
only ever *reads* DNS — it never connects back to your server. So `spitfire`,
behind NAT with nothing open, still gets real certificates. Only a `TXT` record
is touched, so **you don't need any `A` record yet** — part 2 adds that. Part 1
needs nothing but the DNS credential below.

`classesarecode.net` is registered at **Joker.com** on Joker's nameservers
(`x/y/z.ns.joker.com`). Joker's full API (DMAPI) is **reseller-only**, so pick
one of:

**Option A — Joker "Dynamic DNS" (`SVC` mode).** Regular Joker accounts get a
per-domain Dynamic-DNS credential that also covers ACME `TXT` records. In
Joker's DNS settings for `classesarecode.net`, enable **Dynamic DNS** and note
the generated username / password. Then `acme.env`:

```sh
ACME_DNS_PROVIDER=joker
JOKER_API_MODE=SVC
JOKER_USERNAME=<dyndns username>
JOKER_PASSWORD=<dyndns password>
JOKER_PROPAGATION_TIMEOUT=1200
JOKER_POLLING_INTERVAL=30
```

Watch the first cert attempt — if Joker's SVC endpoint won't create a *nested*
`_acme-challenge.ignition.…` label, use Option B.

**Option B — delegate the demo subdomain (robust).** `ignition.classesarecode.net`
is dedicated, so hand *just* its DNS to a provider with a proper free API and
never touch Joker for the demo again:

1. Create the zone `ignition.classesarecode.net` at **[deSEC.io](https://desec.io)**
   (free, DNS-only, first-class Traefik support) or Cloudflare; get an API token.
2. At Joker, add `NS` records delegating the subdomain:
   ```
   ignition.classesarecode.net.  NS  ns1.desec.io.
   ignition.classesarecode.net.  NS  ns2.desec.org.
   ```
3. `acme.env`:
   ```sh
   ACME_DNS_PROVIDER=desec        # or: cloudflare
   DESEC_TOKEN=<api token>        # or: CF_DNS_API_TOKEN=<scoped token>
   ```

The apex `classesarecode.net` and all your other Joker records stay exactly as
they are.

### The edge (Traefik)

Traefik is a reverse proxy. On `spitfire` it owns ports 80 and 443, holds the
TLS certificates, and routes each request to the right container by
**hostname**: the bare apex → `ignition-control`, and later `git.<team>.…` →
that team's Forgejo, `<app>.apps.<team>.…` → that app.

### `ignition-control` and first-run setup

`ignition-control` is the whole Ignition application — one Java service, one
web console (what a signed-in user sees is by their role — platform admin,
team admin, team member — not a separate URL per role), backed by a
PostgreSQL it manages, and driving Docker to build and tear down teams and
apps.

On the **first** start it has no accounts, so it logs a one-time **setup code**
(`IGNITION SETUP — ... enter code: <code>`). You open
`https://ignition.classesarecode.net/setup`, enter that code plus your
email and a password, and that creates the **platform admin**. After that,
`/setup` is gone and you sign in with email + password.

`IGN_SECRET_KEY` (32 bytes, base64) encrypts the per-zone credentials in
Postgres — keep it with your backups.

### Nodes, zones, apps

- **node** — a machine that can run team stacks. Here there is exactly one:
  `spitfire` itself, registered with the endpoint `local` ("the Docker on this
  same box").
- **zone** — one team's fully isolated stack: its own Forgejo (git + CI +
  container registry), a private Docker-in-Docker build engine, a CI runner.
  Identified by a **slug** like `quantum-badgers`. *(Created in part 2.)*
- **app** — a container a team deploys from its repo. *(Part 2.)*

### No SSO

The [target model](docs/exposure.md) puts a single sign-on gateway in front of
every browser request. This demo doesn't deploy that gateway. The consoles
require an email/password login; anything published later (apps, Forgejo) is
open. Fine for a demo, wrong for anything real.

---

## What you'll fill in

| placeholder | what it is | example |
|---|---|---|
| `<SPITFIRE_LAN_IP>` | `spitfire`'s address on your LAN | `192.168.1.20` |
| DNS-01 credential | Joker Dynamic-DNS user/pass (Option A) *or* a deSEC/Cloudflare token (Option B) | see above |
| `<ACME_EMAIL>` | your email, for the Let's Encrypt account | `you@classesarecode.net` |
| SMTP | `IGN_SMTP_*` — the service won't start without it; a `maildev` container is fine for a demo | — |

---

## Step 1 — the DNS-01 credential

Set up **Option A** (Joker Dynamic DNS) or **Option B** (delegate to
deSEC/Cloudflare) from ["The DNS-01 credential"](#the-dns-01-credential-and-why-thats-all-you-need-here)
above. You'll paste the result into `acme.env` in the next step.

Nothing else in DNS is needed for part 1 — no `A` record yet.

---

## Step 2 — bring up the stack

`spitfire` needs **Docker + Docker Compose v2** and nothing else — no host
webserver. Traefik binds the host's `:80` and `:443`, so free them first
(`ss -tlnp | grep -E ':80 |:443 '`; `systemctl disable --now apache2` /
`nginx` if present). You don't have to uninstall anything, just stop it.

The generator (from the callout at the top) has produced `demo/out/ignition.env`
and `demo/out/acme.env`, filled in. On `spitfire`, in the clone:

```sh
cd ignition                               # repo root
cp demo/out/ignition.env .env      && chmod 600 .env
cp demo/out/acme.env    acme.env   && chmod 600 acme.env

docker network create traefik-public
docker volume  create ignition-dynamic   # shared: ignition-control writes it, traefik reads it
mkdir -p ssh-empty                        # node is 'local'; no remote-node SSH keys

docker compose --project-directory . -f templates/traefik-core-compose.yml up -d
docker compose --project-directory . -f templates/ignition-control-compose.yml up -d
```

`--project-directory .` matters: without it Compose reads `.env` / `acme.env`
next to the compose file (`templates/`), not from the repo root.

`.env` carries `BASE_DOMAIN`, `ACME_EMAIL`, `ACME_DNS_PROVIDER`, the SMTP block,
`IGN_PUBLIC_URL`, `IGN_SECRET_KEY`, and `POSTGRES_PASSWORD`.

<details><summary>Doing it by hand instead</summary>

Skip the generator and set the same variables yourself: write a `.env` with
`BASE_DOMAIN=ignition.classesarecode.net`, `ACME_EMAIL=…`,
`ACME_DNS_PROVIDER=…`, `IGN_PUBLIC_URL=https://ignition.classesarecode.net`,
`IGN_SECRET_KEY=$(head -c32 /dev/urandom | base64)`,
`POSTGRES_PASSWORD=$(openssl rand -hex 24)`, and the four `IGN_SMTP_*` values;
and an `acme.env` with the provider lines from Step 1 (Option A: `JOKER_API_MODE=SVC`
+ `JOKER_USERNAME` / `JOKER_PASSWORD` + the two timeout lines; Option B:
`DESEC_TOKEN=…`). Then the same `docker compose … up -d` pair.
</details>

**Create the platform admin.** On first start `ignition-control` prints a
setup code:

```sh
docker compose --project-directory . -f templates/ignition-control-compose.yml \
  logs ignition-control | grep "IGNITION SETUP"
```

Open `https://ignition.classesarecode.net/setup`, enter that code + your
email + a password. (Reaching the URL: see Step 3.)

Watch the first certificate get issued — DNS-01 can take a few minutes while
the `_acme-challenge` `TXT` record propagates (Joker/SVC especially):

```sh
docker compose --project-directory . -f templates/traefik-core-compose.yml logs -f traefik | grep -i acme
```

You want a line about a certificate obtained for `ignition.classesarecode.net` /
`*.ignition.classesarecode.net`. If it errors on *creating* the challenge
record, the provider credential is the problem — with Option A, that's the
signal to switch to Option B.

---

## Step 3 — point your laptop at `spitfire` and validate

The demo hostnames don't resolve anywhere yet. Override them on the machine you
test from — a laptop on the same LAN:

```
# add to /etc/hosts  (Linux/macOS)  or  C:\Windows\System32\drivers\etc\hosts
<SPITFIRE_LAN_IP>   ignition.classesarecode.net
```

Check the cert and reach the console:

```sh
curl -I https://ignition.classesarecode.net/actuator/health
# → HTTP/2 200, no TLS warning = the real Let's Encrypt cert is being served
```

Open **`https://ignition.classesarecode.net/`** — if you haven't done
`/setup` yet it redirects there; otherwise sign in with the email + password
you set. Then:

**Nodes → Register**

| field | value |
|---|---|
| name | `spitfire` |
| endpoint | `local` |
| CPUs / MEM_GB | `spitfire`'s real specs, e.g. `8` / `32` |
| labels | *(leave empty)* |

`local` means `ignition-control` uses the Docker socket it already has
mounted — no SSH involved.

---

## Where you are now

Ignition runs on `spitfire` and the platform console works from your LAN with a
real certificate. The stack configuration is final — **nothing here changes in
part 2.**

**Provisioning a zone is deliberately left for [part 2](demo-2-remote-access.md).**
It makes `ignition-control` talk to the team's Forgejo over its public name
(`git.<slug>.ignition.classesarecode.net`), and CI pushes images to that same
name — both need the public path that part 2 builds.

---

## Part 1 teardown (if you're stopping here)

```sh
cd ignition
docker compose --project-directory . -f templates/ignition-control-compose.yml down
docker compose --project-directory . -f templates/traefik-core-compose.yml down -v      # -v also drops the ACME cert volume
docker network rm traefik-public
```

Remove the `/etc/hosts` line from your laptop.
