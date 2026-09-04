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
> cd demo && ./gen-templates.sh
> cp demo.conf.example demo.conf   # fill it in
> ./render.sh demo.conf            # -> demo/out/ + demo/out/INSTALL.txt
> ```
> It generates `IGN_ADMIN_TOKEN` / `IGN_SECRET_KEY` / `POSTGRES_PASSWORD` and
> the WireGuard keys for you. See [`demo/README.md`](demo/README.md).

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
**hostname**: `admin.…` → `ignition-control`, and later `git.<team>.…` →
that team's Forgejo, `<app>.apps.<team>.…` → that app.

### `ignition-control` and `IGN_ADMIN_TOKEN`

`ignition-control` is the whole Ignition application — one Java service serving
two web consoles (a **platform** console for you, a **zone** console per team)
and driving Docker to build and tear down teams and apps.

`IGN_ADMIN_TOKEN` is a long random string **you generate**. It's the platform
admin password — whoever has it can do anything. You paste it once to sign into
`https://admin.ignition.classesarecode.net/`.

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
still require a token; anything published later is open. Fine for a demo, wrong
for anything real.

---

## What you'll fill in

| placeholder | what it is | example |
|---|---|---|
| `<SPITFIRE_LAN_IP>` | `spitfire`'s address on your LAN | `192.168.1.20` |
| DNS-01 credential | Joker Dynamic-DNS user/pass (Option A) *or* a deSEC/Cloudflare token (Option B) | see above |
| `<ACME_EMAIL>` | your email, for the Let's Encrypt account | `you@classesarecode.net` |
| `<IGN_ADMIN_TOKEN>` | platform admin token you generate | `openssl rand -hex 32` |

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

Then, on `spitfire`:

```sh
git clone https://github.com/johnjoeallen/ignition.git
cd ignition

docker network create traefik-public
mkdir -p ssh-empty                       # the node is 'local'; no remote-node SSH keys needed

# --- core services: the edge Traefik + Watchtower ---
export BASE_DOMAIN=ignition.classesarecode.net
export ACME_EMAIL=<ACME_EMAIL>

# acme.env — one of the two from Step 1:
#
#   Option A (Joker Dynamic DNS)          Option B (deSEC)
#   ---------------------------           ----------------
#   ACME_DNS_PROVIDER=joker               ACME_DNS_PROVIDER=desec
#   JOKER_API_MODE=SVC                    DESEC_TOKEN=<token>
#   JOKER_USERNAME=<dyndns user>
#   JOKER_PASSWORD=<dyndns pass>
#   JOKER_PROPAGATION_TIMEOUT=1200
#   JOKER_POLLING_INTERVAL=30
#
export ACME_DNS_PROVIDER=<from your chosen option>
cat > acme.env <<'ENV'
<paste the provider credential lines here>
ENV
chmod 600 acme.env

docker compose -f templates/traefik-core-compose.yml up -d

# --- the control plane ---
export IGN_ADMIN_TOKEN=$(openssl rand -hex 32)
echo "SAVE THIS -> $IGN_ADMIN_TOKEN"

docker compose -f templates/ignition-control-compose.yml up -d
```

Watch the first certificate get issued — DNS-01 can take a few minutes while
the `_acme-challenge` `TXT` record propagates (Joker/SVC especially):

```sh
docker compose -f templates/traefik-core-compose.yml logs -f traefik | grep -i acme
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
<SPITFIRE_LAN_IP>   admin.ignition.classesarecode.net
```

Check the cert and reach the console:

```sh
curl -I https://admin.ignition.classesarecode.net/actuator/health
# → HTTP/2 200, no TLS warning = the real Let's Encrypt cert is being served
```

Open **`https://admin.ignition.classesarecode.net/`**, sign in with the
`IGN_ADMIN_TOKEN` you saved, then:

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
docker compose -f templates/ignition-control-compose.yml down
docker compose -f templates/traefik-core-compose.yml down -v      # -v also drops the ACME cert volume
docker network rm traefik-public
```

Remove the `/etc/hosts` line from your laptop.
