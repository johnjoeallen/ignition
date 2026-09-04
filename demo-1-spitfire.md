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

---

## The pieces, explained

Read once; the steps refer back to this.

### The DNS API credential (and why that's all you need here)

"Let's Encrypt" issues the free TLS certificates that give a browser its
padlock. To prove you control `ignition.classesarecode.net`, it asks for a
temporary `TXT` record at `_acme-challenge.ignition.classesarecode.net`. Traefik
creates and removes that record automatically **if you give it an API credential
for the parent zone's DNS host** — here, `classesarecode.net` at Joker.

`classesarecode.net` uses **Joker.com**'s nameservers (`x/y/z.ns.joker.com`), so
the Traefik provider is **`joker`**, driving Joker's DMAPI (it operates on the
registered domain and can set records for any subdomain):

- **`ACME_DNS_PROVIDER=joker`**
- **`acme.env`** — one of:
  - `JOKER_API_KEY=<key>` — a dedicated key from the Joker account
    (*Profile → DMAPI / API keys*; DMAPI access may need enabling first), **or**
  - `JOKER_USERNAME=<login>` + `JOKER_PASSWORD=<password>` — your Joker web login.
- Joker's DMAPI is slow to publish, so also set
  `JOKER_PROPAGATION_TIMEOUT=1200` and `JOKER_POLLING_INTERVAL=30`.

This challenge type is **DNS-01**. Its key property: the Certificate Authority
only ever *reads* DNS — it never connects back to your server. So `spitfire`,
behind NAT with nothing open, still gets real certificates. And because only a
`TXT` record is involved, **you don't need any `A` record yet** — part 2 adds
the public `A` record. Part 1 needs nothing from DNS but the Joker credential.

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
| `<JOKER_KEY>` | Joker.com DMAPI key (or use `JOKER_USERNAME`/`JOKER_PASSWORD`) | `abcd1234…` |
| `<ACME_EMAIL>` | your email, for the Let's Encrypt account | `you@classesarecode.net` |
| `<IGN_ADMIN_TOKEN>` | platform admin token you generate | `openssl rand -hex 32` |

---

## Step 1 — Joker API credential

In the **Joker.com** account: enable DMAPI access if it isn't already, and
create an API key (*Profile → DMAPI*). That key is `<JOKER_KEY>`. (Or skip the
key and use your Joker login as `JOKER_USERNAME` / `JOKER_PASSWORD`.)

Nothing else in DNS is needed for part 1.

---

## Step 2 — bring up the stack

On `spitfire` (needs Docker + Docker Compose v2):

```sh
git clone https://github.com/johnjoeallen/ignition.git
cd ignition

docker network create traefik-public
mkdir -p ssh-empty                       # the node is 'local'; no remote-node SSH keys needed

# --- core services: the edge Traefik + Watchtower ---
export BASE_DOMAIN=ignition.classesarecode.net
export ACME_EMAIL=<ACME_EMAIL>
export ACME_DNS_PROVIDER=joker

cat > acme.env <<'ENV'
JOKER_API_KEY=<JOKER_KEY>
JOKER_PROPAGATION_TIMEOUT=1200
JOKER_POLLING_INTERVAL=30
ENV
chmod 600 acme.env

docker compose -f templates/traefik-core-compose.yml up -d

# --- the control plane ---
export IGN_ADMIN_TOKEN=$(openssl rand -hex 32)
echo "SAVE THIS -> $IGN_ADMIN_TOKEN"

docker compose -f templates/ignition-control-compose.yml up -d
```

Watch the first certificate get issued (with Joker's DMAPI this can take
several minutes while the `_acme-challenge` `TXT` record propagates through
`*.ns.joker.com`):

```sh
docker compose -f templates/traefik-core-compose.yml logs -f traefik | grep -i acme
```

You want a line about a certificate obtained for `ignition.classesarecode.net` /
`*.ignition.classesarecode.net`.

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
