# Demo configuration — public access point + one LAN node (`spitfire`)

A concrete, single-node setup for demoing Ignition. `hetzner` is only a public
door; `spitfire` on your LAN runs everything.

- **`hetzner`** — public, an **access point** only: a WireGuard listener plus an
  `nft` rule that forwards the demo's `:80` / `:443` straight to `spitfire`. It
  terminates nothing and holds no Ignition config. The box already runs **web
  (`:80`/`:443`), mail (`:143`, …) and SSH on its main IP**, so the demo is
  given its **own second IP** and none of those are touched — see
  [Step 2](#step-2--hetzner-root-server-demo-ip-and-firewall).
- **`spitfire`** — one box on your LAN, behind NAT, **no inbound**. Runs the
  whole Ignition stack: the edge Traefik (TLS + ACME), `ignition-control`, and
  every zone and app.

The apex is **`ignition.theresnolimits.net`**. No SSO — every app and every
Forgejo is reachable by anyone who can resolve the domain. Fine for a demo, not
for anything real; see [Security notes](#security-notes).

```
              internet                         internet
                 │  Apache's sites               │  *.ignition.theresnolimits.net
                 │  :80/:443                      │  :80/:443
     ┌───────────▼───────────────────────────────▼──────────┐
     │  hetzner                                              │  public
     │   <PUBLIC_IP>  ──►  Apache (untouched)                │
     │   <DEMO_IP>    ──►  nft DNAT :80/:443 → 10.44.0.11    │  ← added for the demo
     │   WireGuard listener  10.44.0.1                       │
     └───────────────────────────┬──────────────────────────┘
                                 │  WireGuard (spitfire dials out; no router port-forward)
                     ┌───────────▼─────────────┐
                     │  spitfire  10.44.0.11   │   LAN, behind NAT
                     │  ── Traefik edge ──     │   • owns :80/:443, all TLS, ACME DNS-01
                     │  ── ignition-control ── │   • the platform + zone consoles
                     │  ── zones / apps ──     │   • Forgejo · DinD · runner · deployed apps
                     └─────────────────────────┘
```

> **Why this shape and not "controller on hetzner".** The
> [target model](docs/exposure.md) puts the edge Traefik and SSO gateway on the
> public box. That needs template work that isn't done yet (task 4). For a demo,
> the shortcut is: `spitfire` runs everything exactly as a single box would, and
> `hetzner` is a dumb L4 forwarder. Zero changes to the compose templates.
> ACME **DNS-01** never needs an inbound connection, so `spitfire` gets real
> Let's Encrypt certs from behind NAT.

---

## What you fill in

| placeholder | example | notes |
|---|---|---|
| `<PUBLIC_IP>` | the box's existing address | Apache's IP — stays as it is |
| `<DEMO_IP>` | a **second** public IPv4 on `hetzner` | dedicated to the demo (Step 2) |
| `<DNS_PROVIDER>` | `hetzner`, `cloudflare`, `route53`, … | one of [Traefik's DNS providers](https://doc.traefik.io/traefik/https/acme/#providers) |
| `<DNS_TOKEN_VAR>=<value>` | `HETZNER_API_KEY=abc…` | the credential env var(s) that provider needs |
| `<ACME_EMAIL>` | `you@theresnolimits.net` | Let's Encrypt account email |

DNS for `theresnolimits.net` must be at a provider with an API, so the ACME
DNS-01 challenge can add a TXT record.

---

## Step 1 — WireGuard

Install WireGuard on both machines (`apt install wireguard`).

Generate a keypair on each:

```sh
wg genkey | tee privatekey | wg pubkey > publickey
```

Refer to them as `HETZNER_PRIV` / `HETZNER_PUB` and `SPITFIRE_PRIV` / `SPITFIRE_PUB`.

### `hetzner:/etc/wireguard/wg0.conf`

```ini
[Interface]
Address    = 10.44.0.1/24
ListenPort = 51820
PrivateKey = <HETZNER_PRIV>

# load / unload the demo's DNAT table with the tunnel
PostUp   = nft -f /etc/wireguard/forward.nft
PostDown = nft delete table ip ignition_demo

[Peer]   # spitfire
PublicKey  = <SPITFIRE_PUB>
AllowedIPs = 10.44.0.11/32
```

### `hetzner:/etc/wireguard/forward.nft`

Its own table (`ignition_demo`), so it never collides with an existing
firewall. Only traffic aimed at **`<DEMO_IP>`** is touched — Apache on
`<PUBLIC_IP>` is untouched.

```nft
#!/usr/sbin/nft -f

table ip ignition_demo {
  chain prerouting {
    type nat hook prerouting priority dstnat; policy accept;
    ip daddr <DEMO_IP> tcp dport { 80, 443 } dnat to 10.44.0.11
  }
  chain postrouting {
    type nat hook postrouting priority srcnat; policy accept;
    ip daddr 10.44.0.11 tcp dport { 80, 443 } oifname "wg0" masquerade
  }
}
```

Forwarding must be on (persist it, don't rely only on a `PostUp`):

```sh
echo 'net.ipv4.ip_forward=1' > /etc/sysctl.d/99-ignition-demo.conf
sysctl --system
```

If `hetzner` runs a host firewall with a **`drop` policy on the `forward`
hook** (check: `nft list ruleset | grep -A2 'hook forward'`), add accepts for
the demo flow to *that* table — a separate table's `accept` can't override
another's `drop`:

```nft
# in your existing filter table's forward chain
iifname != "wg0" ip daddr 10.44.0.11 tcp dport { 80, 443 } accept
oifname != "wg0" ct state established,related accept
```

With no host firewall (or all-`accept` policies — the default), the
`ignition_demo` table above is all you need.

### `spitfire:/etc/wireguard/wg0.conf`

```ini
[Interface]
Address    = 10.44.0.11/24
PrivateKey = <SPITFIRE_PRIV>

[Peer]   # hetzner
PublicKey           = <HETZNER_PUB>
Endpoint            = <PUBLIC_IP>:51820
AllowedIPs          = 10.44.0.0/24
PersistentKeepalive = 25
```

`AllowedIPs = 10.44.0.0/24` keeps only tunnel traffic on WireGuard — your LAN and
normal internet on `spitfire` are untouched.

Bring it up on both, then verify:

```sh
sudo systemctl enable --now wg-quick@wg0
sudo wg show                       # a recent handshake, on both ends
ping -c3 10.44.0.11                 # from hetzner
ping -c3 10.44.0.1                  # from spitfire
```

---

## Step 2 — hetzner root server: demo IP and firewall

The box already serves `:80`, `:443`, `:143` and SSH on `<PUBLIC_IP>`. Nothing
below edits any existing service, IP binding, or firewall rule.

### Why this is safe by construction

- The demo lives on a **separate IP** (`<DEMO_IP>`). Every existing service
  keeps listening on `<PUBLIC_IP>` exactly as now.
- The forwarding rule is its **own nftables table** (`ignition_demo`). nft
  tables are independent — loading or deleting one cannot alter or remove a
  rule in another.
- Its `prerouting` rule matches **only `ip daddr <DEMO_IP>` tcp dport 80/443**.
  A packet to `<PUBLIC_IP>:80` / `:443` / `:143` / `:22` never matches it and is
  handled exactly as today.

The **one** unavoidable change is an inbound `udp/51820` accept for the
WireGuard listener — and only if your host firewall has a default-drop input
policy. Everything else is additive and isolated.

### 1. Additional IP (Robot)

Order a **single additional IPv4** for the server (Robot → *IPs* → *Order additional IP*).
Hetzner routes it to your server's main IP — no separate MAC needed for host use.
Note whether Robot shows it as *same subnet as the main IP* (usual — bind as
`/32`) or *separate subnet* (it will also give a gateway; see Hetzner's "Additional
IP addresses" doc for the `pointopoint` form).

### 2. Bind it on Debian

Add the address alongside the existing one — don't replace anything.

```ini
# /etc/systemd/network/<existing-file>.network.d/demo-ip.conf   (systemd-networkd)
[Network]
Address=<DEMO_IP>/32
```
```sh
networkctl reload && networkctl reconfigure <iface>
```

`ifupdown` box instead:

```
# /etc/network/interfaces.d/60-demo-ip
up   ip addr add <DEMO_IP>/32 dev <iface>
down ip addr del <DEMO_IP>/32 dev <iface>
```
```sh
ip addr add <DEMO_IP>/32 dev <iface>      # apply now, no restart of the main iface
```

Verify from another host: `ping <DEMO_IP>`, and `curl -I http://<DEMO_IP>` still
reaches **Apache** at this point (nothing forwards yet). Existing services
unaffected — re-check `https://<your-site>` and IMAP.

### 3. Hetzner Robot firewall (only if you use it)

Dedicated servers can have Hetzner's **stateless** hardware firewall (Robot →
*Firewall*). If yours is **not** active, skip this — don't activate it now.

If it **is** active, its template already lists your `:80` / `:443` / `:143` /
SSH rules. **Add**, keeping all existing rows:

| version | protocol | dst port | action |
|---|---|---|---|
| IPv4 | `udp` | `51820` | accept |

(It filters all the server's IPs; the `:80`/`:443` rows you already have cover
`<DEMO_IP>` too. Stateless → make sure your template has the usual
`tcp flags` established/`ack` accept row it needs, which it will if SSH works today.)

### 4. Host nftables

First look at what's there:

```sh
nft list ruleset | less
nft -a list ruleset | grep -E 'hook (input|forward)'      # note any 'policy drop'
```

**Open a second SSH session and leave it connected** before the next step, plus
a dead-man's rollback:

```sh
# reboots in 10 min unless you cancel with `shutdown -c` after confirming access
shutdown -r +10 "firewall change rollback"
```

- **`ignition_demo` table** (from Step 1) — loads with `wg0`. Isolated; nothing
  to reconcile with existing tables.
- **`ip_forward`** — `/etc/sysctl.d/99-ignition-demo.conf` (Step 1). Check it
  doesn't fight an existing `net.ipv4.ip_forward=0` in another file; the highest
  numbered file wins, `99-` is high.
- **Input policy `drop`?** Add just the one rule to the existing table/chain
  (append, don't rewrite the file):

  ```sh
  nft add rule inet <table> <input-chain> udp dport 51820 accept
  ```
- **Forward policy `drop`?** Add the demo flow to the existing forward chain:

  ```sh
  nft add rule inet <table> <forward-chain> ip daddr 10.44.0.11 tcp dport { 80, 443 } accept
  nft add rule inet <table> <forward-chain> ip saddr 10.44.0.11 ct state established,related accept
  ```

Confirm SSH still works in a fresh connection, then `shutdown -c`. Persist:
append the same lines to whatever file feeds `nft` at boot (often
`/etc/nftables.conf` or a file in `/etc/nftables.d/`), or
`nft list ruleset > /etc/nftables.conf` if that's how the box already does it.

`spitfire` needs **no** inbound rules and **no** router port-forwarding — it
dials out.

---

## Step 3 — DNS

One record at your provider, pointed at the **demo IP**:

```
*.ignition.theresnolimits.net.   A   <DEMO_IP>
ignition.theresnolimits.net.     A   <DEMO_IP>      # optional, only if you want the apex
```

The wildcard matches at any depth (RFC 4592), so
`admin.ignition.theresnolimits.net`,
`git.<slug>.ignition.theresnolimits.net`,
`admin.<slug>.ignition.theresnolimits.net`, and
`<app>.apps.<slug>.ignition.theresnolimits.net` all resolve with no further
records — provisioning a zone adds zero DNS.

Create an **API token** for the `theresnolimits.net` zone; that's what feeds the
ACME DNS-01 challenge.

---

## Step 4 — bring up Ignition on `spitfire`

```sh
git clone https://github.com/johnjoeallen/ignition.git
cd ignition

docker network create traefik-public
mkdir -p ssh-empty                       # node is local; no remote-node keys needed

# --- core services: edge Traefik + Watchtower ---
export BASE_DOMAIN=ignition.theresnolimits.net
export ACME_EMAIL=<ACME_EMAIL>
export ACME_DNS_PROVIDER=<DNS_PROVIDER>
printf '<DNS_TOKEN_VAR>=<value>\n' > acme.env      # DNS API creds for ACME DNS-01
chmod 600 acme.env

docker compose -f templates/traefik-core-compose.yml up -d

# --- the control plane ---
export IGN_ADMIN_TOKEN=$(openssl rand -hex 32)
echo "SAVE THIS: $IGN_ADMIN_TOKEN"

docker compose -f templates/ignition-control-compose.yml up -d
```

Watch the first cert issue (DNS-01 can take a minute for propagation):

```sh
docker compose -f templates/traefik-core-compose.yml logs -f traefik | grep -i acme
```

Then, from anywhere:

```sh
curl -I https://admin.ignition.theresnolimits.net/actuator/health     # 200, valid cert
```

Traffic path: `client → <DEMO_IP>:443 → (nft DNAT) → wg0 → spitfire
10.44.0.11:443 → Traefik → ignition-control`.

---

## Step 5 — register the node and provision a zone

Open **`https://admin.ignition.theresnolimits.net/`**, sign in with `IGN_ADMIN_TOKEN`.

1. **Nodes → Register**

   | field | value |
   |---|---|
   | name | `spitfire` |
   | endpoint | `local` |
   | CPUs / MEM_GB | `spitfire`'s real specs (e.g. `8` / `32`) |
   | labels | *(leave empty)* |

   `local` = the Docker socket `ignition-control` already has mounted. No SSH,
   no WireGuard involved for control — the tunnel only carries the forwarded
   web traffic.

2. **Zones → Provision** — a slug per demo team, e.g. `quantum-badgers`. The
   scheduler places it on `spitfire` (the only node). It stands up Forgejo +
   DinD + a runner, creates the `zoneadmin` account, and mints the tokens
   (~1–2 min). Or use **Roster** and paste several slugs at once.

3. On the zone's page, copy its **zone token** and **deploy token**. Hand the
   "team" the zone token — they sign in at
   `https://admin.quantum-badgers.ignition.theresnolimits.net/`, which is their
   whole surface.

Each zone's Forgejo router requests its own
`*.<slug>.ignition.theresnolimits.net` +
`*.apps.<slug>.ignition.theresnolimits.net` cert automatically (those names are
two labels deep, past the apex wildcard cert).

---

## Step 6 — deploy an app (the "team" side)

In the zone's Forgejo (`https://git.quantum-badgers.ignition.theresnolimits.net/`):

1. Create a repo with a `Dockerfile` and
   `.forgejo/workflows/deploy.yml` (copy [`examples/deploy.yml`](examples/deploy.yml)).
2. Set the repo variables / secrets it needs: `REGISTRY`, `CONTROL_URL`,
   `APP_NAME`, `APP_PORT`, `DEPLOY_TOKEN` (the deploy token from step 5), and an
   optional `FORGEJO_TOKEN`.
3. In the **zone console → Repositories**, click **Release**. `ignition-control`
   reads the commits since the last tag, picks the semver bump (Conventional
   Commits), and tags `vX.Y.Z` on `main`. That tag builds, pushes to
   `git.quantum-badgers.ignition.theresnolimits.net`, and `POST /deploy`s the app.
4. It comes up at
   `https://<APP_NAME>.apps.quantum-badgers.ignition.theresnolimits.net/`.

A plain push to `main` does **not** deploy — only a release tag does.

---

## Security notes

- **No authentication anywhere.** Every deployed app, every zone's Forgejo web
  UI, and both consoles (bar the token) are open to anyone who resolves the
  domain. The consoles still need their bearer token; nothing else does.
- **`IGN_ADMIN_TOKEN` is the keys to everything.** It's the bearer header /
  session cookie for `admin.ignition.theresnolimits.net`. Treat it like a root
  password; rotate it after the demo (`docker compose … up -d` with a new value).
- **Client IPs are lost.** Every request reaches `spitfire` as `10.44.0.1`
  (the `nft` masquerade). Fine for a demo; means no meaningful per-client
  logging or IP allow-listing.
- **`traefik-public` is one flat network on `spitfire`** — a deployed app
  container can reach another zone's Forgejo by IP. The untrusted code is the
  app. Don't run a demo with app code you don't trust and expect zone isolation
  to hold at the network layer.
- **Take the DNS record down when you're done** so the names stop resolving to
  your box, and release the demo IP.

---

## Teardown

```sh
# on spitfire
cd ignition
# destroy every zone from the console (Roster → paste slugs → Teardown), then:
docker compose -f templates/ignition-control-compose.yml down
docker compose -f templates/traefik-core-compose.yml down -v      # -v drops the acme volume
docker network rm traefik-public

# both machines
sudo systemctl disable --now wg-quick@wg0

# on hetzner: release the demo IP in the Hetzner Cloud console
# DNS: delete the *.ignition.theresnolimits.net record
```

---

## How this differs from the full model

| | this demo | [target model](docs/exposure.md) |
|---|---|---|
| TLS terminates on | `spitfire` | the public controller |
| `hetzner` runs | `nft` DNAT only | edge Traefik + SSO gateway + `ignition-control` |
| node reachability | `spitfire` holds `:443` (behind the forward) | node Traefik internal-only `:80`, edge routes by `Host` |
| auth | none | one `forward-auth` gateway for all browser traffic |
| control-plane location | on the node | on the controller |
| template changes | none | task 4 |

Moving to the full model later doesn't invalidate any of this: same DNS record,
same certs, same zones. You'd relocate `ignition-control` + the edge Traefik to
`hetzner`, switch `spitfire`'s Traefik to internal-only, and add the
`forward-auth` container.

### Alternative to the `nft` forwarder

An SSH reverse tunnel from `spitfire` does the same job (needs
`GatewayPorts clientspecified` in `hetzner:/etc/ssh/sshd_config`):

```sh
# on spitfire, e.g. as a systemd unit with autossh
ssh -N -R <DEMO_IP>:80:localhost:80 -R <DEMO_IP>:443:localhost:443 <you>@<PUBLIC_IP>
```

Binding to `<DEMO_IP>` keeps it off Apache's `<PUBLIC_IP>`. Drop the `PostUp nft`
/ `PostDown` lines from `wg0.conf` if you use this. WireGuard + `nft` is steadier
for an all-day demo; the SSH tunnel is quicker to throw up for an hour.
