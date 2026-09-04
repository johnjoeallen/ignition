# Demo configuration — public access point + one LAN node (`spitfire`)

A complete, worked setup for demoing Ignition with **one machine on your LAN**
doing all the work and **a public box acting only as a doorway**. It assumes
you have not run Ignition before, so it explains each moving part.

- **`spitfire`** — a box on your LAN, behind your home/office NAT, with **no
  inbound access from the internet**. It runs the *entire* Ignition stack: the
  edge Traefik (TLS), `ignition-control` (the control plane + web consoles), and
  every team's Forgejo, build engine, runner, and deployed apps.
- **`hetzner`** — a public root server you already run (web on `:80`/`:443`,
  mail on `:143`, SSH). For the demo it does exactly two things: it is the
  **WireGuard endpoint** `spitfire` dials out to, and it **forwards** one spare
  public IP's `:80`/`:443` down that tunnel to `spitfire`. It terminates no TLS,
  holds no Ignition config, and none of its existing services are touched.

The public apex is **`ignition.theresnolimits.net`**. There is **no SSO** in
this demo — anything published is reachable by anyone who can resolve the name.
Fine for a demo, wrong for anything real; see [Security notes](#security-notes).

```
        Apache's existing sites            *.ignition.theresnolimits.net
        (internet → <PUBLIC_IP>)           (internet → <DEMO_IP>)
                 │  :80/:443                        │  :80/:443
     ┌───────────▼──────────────────────────────────▼────────┐
     │  hetzner  (root server, unchanged services)            │
     │   <PUBLIC_IP>  →  Apache / Dovecot / sshd  (untouched) │
     │   <DEMO_IP>    →  nft DNAT :80/:443  ─┐                 │
     │   WireGuard listener :51820  10.44.0.1 │                │
     └───────────────────────────────────────┼───────────────┘
                                             │  WireGuard tunnel
                            spitfire dials OUT to hetzner:51820
                            (your router needs no port-forward)
                                             │
                     ┌───────────────────────▼─┐
                     │  spitfire   10.44.0.11    │  LAN, behind NAT
                     │  ── Traefik (edge) ──     │  owns :80/:443, all TLS, ACME DNS-01
                     │  ── ignition-control ──   │  platform + zone web consoles
                     │  ── zones ──              │  per team: Forgejo · DinD · runner
                     │  ── apps ──               │  the containers teams deploy
                     └──────────────────────────┘
```

> **Why not put the control plane on `hetzner`?** The
> [target architecture](docs/exposure.md) does exactly that — the public box
> runs the edge Traefik and an SSO gateway. Wiring that into the compose
> templates is unfinished work (task 4). Until then, the shortest path to a
> working demo is: `spitfire` runs everything as if it were a single box, and
> `hetzner` is a dumb layer-4 forwarder. No template changes.

---

## The pieces, explained

Read this once; the steps below refer back to it.

### The two IPs on `hetzner`

- **`<PUBLIC_IP>`** — the address `hetzner` already has. Apache, Dovecot and
  sshd listen on it. **Nothing in this guide changes that.**
- **`<DEMO_IP>`** — a *second*, separate public IPv4 you add to `hetzner` just
  for the demo (Hetzner calls it an "additional IP", ~€1–2/mo, ordered in
  Robot). Giving the demo its own address is what lets the forwarding rule be
  written as "anything arriving for `<DEMO_IP>` goes to `spitfire`" without ever
  matching traffic for the real services. When the demo is over you hand the IP
  back.

### The DNS wildcard record

You create **one** DNS record: `*.ignition.theresnolimits.net → <DEMO_IP>`.

A `*` record is a catch-all: it answers for **any** name under
`ignition.theresnolimits.net`, at **any depth** (this is standard DNS —
RFC 4592). So `admin.ignition.theresnolimits.net`,
`git.quantum-badgers.ignition.theresnolimits.net`, and
`paywise.apps.quantum-badgers.ignition.theresnolimits.net` all resolve to
`<DEMO_IP>` from that single record. This is why **creating a team ("zone")
never requires touching DNS** — the names already resolve.

### The DNS API token

"Let's Encrypt" issues the free TLS certificates that make browsers show a
padlock instead of a warning. To prove you control `theresnolimits.net`,
Let's Encrypt asks you to create a specific temporary `TXT` record in the
domain's DNS. Traefik does this automatically **if you give it an API
credential for wherever `theresnolimits.net`'s DNS is hosted** — a token /
key that lets a program add and remove records in that zone.

- **`<DNS_PROVIDER>`** — the short name Traefik uses for your DNS host
  (`cloudflare`, `route53`, `hetzner`, `gandiv5`, `desec`, … — the full list is
  [here](https://doc.traefik.io/traefik/https/acme/#providers)).
- **`<DNS_TOKEN_VAR>=<value>`** — the environment variable(s) that provider
  needs, e.g. `CF_DNS_API_TOKEN=…` for Cloudflare, `HETZNER_API_KEY=…` for
  Hetzner DNS. You put these in a file called `acme.env`.

This challenge type is called **DNS-01**. Its key property for this demo: the
Certificate Authority only ever reads DNS — **it never connects back to your
server**. That is what lets `spitfire`, sitting behind NAT with no inbound,
still get real certificates.

### WireGuard

A small, modern VPN. Here it builds one encrypted link between `hetzner`
(`10.44.0.1`) and `spitfire` (`10.44.0.11`). `spitfire` **dials out** to
`hetzner:51820`, so your router needs no configuration. Once up, `hetzner` can
send packets to `spitfire` even though `spitfire` accepts nothing from the
internet directly.

### The `nft` forward

`nft` (nftables) is Linux's built-in packet filter/NAT. The demo adds a tiny,
self-contained rule set on `hetzner`: *"a TCP connection arriving for
`<DEMO_IP>` on port 80 or 443 → rewrite its destination to `10.44.0.11` and
send it down the WireGuard tunnel."* It is a separate nft *table*
(`ignition_demo`) so it cannot interfere with any firewall `hetzner` already
runs.

### The edge (Traefik) on `spitfire`

Traefik is a reverse proxy. On `spitfire` it owns ports 80 and 443, holds all
the TLS certificates, and routes each incoming request to the right container
by **hostname**: `admin.…` → `ignition-control`, `git.<team>.…` → that team's
Forgejo, `<app>.apps.<team>.…` → that app's container.

### `ignition-control` and `IGN_ADMIN_TOKEN`

`ignition-control` is the whole Ignition application — one Java service. It
serves two web consoles (a **platform** console for you, a **zone** console for
each team) and drives Docker to create and tear down teams and apps.

`IGN_ADMIN_TOKEN` is a long random string **you generate**. It is the platform
admin password — whoever holds it can do anything. You paste it once to sign
into `https://admin.ignition.theresnolimits.net/`.

### Nodes, zones, apps

- **node** — a machine that can run team stacks. In this demo there is exactly
  one: `spitfire` itself, registered with the endpoint `local` (meaning "the
  Docker on this same box").
- **zone** — one team's fully isolated stack: its own Forgejo (git + CI +
  container registry), its own private Docker-in-Docker build engine, its own
  CI runner. Identified by a **slug** like `quantum-badgers`. Creating one is a
  single click / API call and takes 1–2 minutes.
- **app** — a container a team deploys from its repo. A zone can run many;
  each gets `https://<name>.apps.<slug>.ignition.theresnolimits.net/`.

### No SSO

The [target model](docs/exposure.md) puts a single sign-on gateway in front of
every browser request. This demo simply **doesn't deploy that gateway**. The
consoles still require `IGN_ADMIN_TOKEN` / a zone token; everything else
(published apps, Forgejo web UIs) is open to the world.

---

## What you'll fill in

| placeholder | what it is | example |
|---|---|---|
| `<PUBLIC_IP>` | `hetzner`'s existing address — unchanged | `203.0.113.10` |
| `<DEMO_IP>` | the extra public IPv4 you add to `hetzner` for the demo | `203.0.113.55` |
| `<SPITFIRE_LAN_IP>` | `spitfire`'s address on your LAN | `192.168.1.20` |
| `<DNS_PROVIDER>` | Traefik's name for your DNS host | `cloudflare` |
| `<DNS_TOKEN_VAR>=<value>` | the API credential that provider needs | `CF_DNS_API_TOKEN=abc123…` |
| `<ACME_EMAIL>` | your email, for the Let's Encrypt account | `you@theresnolimits.net` |
| `<HETZNER_PRIV>` / `<HETZNER_PUB>` | WireGuard keypair generated on `hetzner` | (Step B4) |
| `<SPITFIRE_PRIV>` / `<SPITFIRE_PUB>` | WireGuard keypair generated on `spitfire` | (Step B4) |
| `<IGN_ADMIN_TOKEN>` | platform admin token you generate | `openssl rand -hex 32` |

---

## Build order

Do it in two stages. **Stage A gets the stack running and validated entirely on
your LAN.** Only then do you wire up **Stage B, the public path.** Nothing in
Stage A has to be redone — the stack configuration is identical before and
after.

```
Stage A  (spitfire, on the LAN)          Stage B  (hetzner + the tunnel)
  A1  DNS record + API token               B1  add <DEMO_IP> to hetzner
  A2  bring up traefik-core + control       B2  bind it in Debian
  A3  local override + validate:            B3  Hetzner Robot firewall (if any)
      certs, console login, register        B4  WireGuard on both machines
      the node                              B5  nft forward + host firewall
                                            B6  remove the local override,
                                                test from the internet
                                            then: provision zones, deploy apps
```

> **Why zones come after Stage B:** provisioning a team makes
> `ignition-control` talk to that team's Forgejo over its public name
> (`git.<slug>.ignition.theresnolimits.net`), and CI pushes images to the same
> name. Those only resolve to something reachable once the public path exists.
> The stack itself, cert issuance, and the platform console do **not** need it —
> those you can fully check in Stage A.

---

## Stage A — `spitfire` on the LAN

### A1. DNS record and API token

At whoever hosts DNS for `theresnolimits.net`:

```
*.ignition.theresnolimits.net.   A   <DEMO_IP>
ignition.theresnolimits.net.     A   <DEMO_IP>     # optional: only if you want the bare apex to resolve
```

`<DEMO_IP>` won't route until Stage B — that's fine. Cert issuance uses DNS-01
(a `TXT` record), which works the moment the API token is valid, regardless of
where the `A` record points.

Create an **API token** scoped to the `theresnolimits.net` zone with permission
to edit records. Note the provider's Traefik name (`<DNS_PROVIDER>`) and which
env var holds the token (`<DNS_TOKEN_VAR>`).

### A2. Bring up the stack

On `spitfire` (needs Docker + Docker Compose v2):

```sh
git clone https://github.com/johnjoeallen/ignition.git
cd ignition

docker network create traefik-public
mkdir -p ssh-empty                       # the node is 'local'; no remote-node SSH keys needed

# --- core services: the edge Traefik + Watchtower ---
export BASE_DOMAIN=ignition.theresnolimits.net
export ACME_EMAIL=<ACME_EMAIL>
export ACME_DNS_PROVIDER=<DNS_PROVIDER>
printf '<DNS_TOKEN_VAR>=<value>\n' > acme.env
chmod 600 acme.env

docker compose -f templates/traefik-core-compose.yml up -d

# --- the control plane ---
export IGN_ADMIN_TOKEN=$(openssl rand -hex 32)
echo "SAVE THIS -> $IGN_ADMIN_TOKEN"

docker compose -f templates/ignition-control-compose.yml up -d
```

Watch the first certificate get issued (DNS-01 can take a minute while the
`TXT` record propagates):

```sh
docker compose -f templates/traefik-core-compose.yml logs -f traefik | grep -i acme
```

You're looking for a line about a certificate obtained for
`ignition.theresnolimits.net` / `*.ignition.theresnolimits.net`.

### A3. Point your laptop at `spitfire` and validate

The demo hostnames resolve to `<DEMO_IP>` (dead until Stage B). Override that on
the machine you're testing from — your laptop, on the same LAN:

```
# add to /etc/hosts  (Linux/macOS)  or  C:\Windows\System32\drivers\etc\hosts
<SPITFIRE_LAN_IP>   admin.ignition.theresnolimits.net
```

Now check the cert and reach the console:

```sh
curl -I https://admin.ignition.theresnolimits.net/actuator/health
# → HTTP/2 200, and no TLS warning = the real Let's Encrypt cert is being served
```

Open **`https://admin.ignition.theresnolimits.net/`** in a browser, sign in
with the `IGN_ADMIN_TOKEN` you saved, then:

**Nodes → Register**

| field | value |
|---|---|
| name | `spitfire` |
| endpoint | `local` |
| CPUs / MEM_GB | `spitfire`'s real specs, e.g. `8` / `32` |
| labels | *(leave empty)* |

`local` means `ignition-control` uses the Docker socket it already has mounted —
no SSH, and the WireGuard tunnel is not involved in controlling Docker, only in
carrying forwarded web traffic.

Stop here in Stage A. Provisioning a zone needs the public path (see the note in
[Build order](#build-order)).

---

## Stage B — the public path via `hetzner`

`hetzner` already serves `:80`, `:443`, `:143` and SSH on `<PUBLIC_IP>`. Nothing
below edits an existing service, IP binding, or firewall rule. It is safe by
construction because:

- the demo lives entirely on the **separate** `<DEMO_IP>`;
- the forwarding is its **own** nft table (`ignition_demo`) — nft tables are
  independent, so adding or removing one cannot change a rule in another;
- that table only matches `ip daddr <DEMO_IP> tcp dport {80,443}` — a packet to
  `<PUBLIC_IP>:80` / `:443` / `:143` / `:22` never matches it.

The **one** unavoidable addition is an inbound `udp/51820` accept for WireGuard,
and only if `hetzner`'s host firewall defaults to dropping input.

### B1. Add `<DEMO_IP>` in Robot

Order a **single additional IPv4** for the server (Robot → *IPs* →
*Order additional IP*). Hetzner routes it to the server's main interface — no
separate MAC address is needed to use it on the host itself. Robot will tell you
whether it's in the **same subnet** as `<PUBLIC_IP>` (the usual case — bind it
as `/32`) or a **separate subnet** (it then also gives you a gateway; use the
`pointopoint` form from Hetzner's "Additional IP addresses" doc).

### B2. Bind it on Debian

Add it *alongside* the existing address — never replace the main interface's
config.

**systemd-networkd:**

```ini
# /etc/systemd/network/<existing-iface-file>.network.d/demo-ip.conf
[Network]
Address=<DEMO_IP>/32
```
```sh
networkctl reload
networkctl reconfigure <iface>          # <iface> e.g. eth0 / enp0s31f6
```

**ifupdown (`/etc/network/interfaces`):**

```
# /etc/network/interfaces.d/60-demo-ip
up   ip addr add <DEMO_IP>/32 dev <iface>
down ip addr del <DEMO_IP>/32 dev <iface>
```
```sh
ip addr add <DEMO_IP>/32 dev <iface>    # apply immediately, main iface not restarted
```

Verify — from another host:

```sh
ping <DEMO_IP>
curl -I http://<DEMO_IP>/               # still reaches Apache; nothing forwards yet
```

Re-check your real site and IMAP. Everything should be exactly as before.

### B3. Hetzner Robot firewall (only if you use it)

Hetzner offers a **stateless** hardware firewall (Robot → *Firewall*). If it is
**not** active on this server, leave it off — do not enable it now.

If it **is** active, its rule template already permits your `:80` / `:443` /
`:143` / SSH. **Add one row, keep every existing row:**

| version | protocol | dst port | action |
|---|---|---|---|
| IPv4 | `udp` | `51820` | accept |

It filters all of the server's IPs, so the existing `:80` / `:443` rows already
cover `<DEMO_IP>`. (Because it's stateless it also needs the "TCP established"
accept row — which it already has if SSH works today.)

### B4. WireGuard on both machines

Install: `apt install wireguard` on both.

Generate a keypair on **each** machine:

```sh
wg genkey | tee privatekey | wg pubkey > publickey
```

Call `hetzner`'s pair `<HETZNER_PRIV>` / `<HETZNER_PUB>` and `spitfire`'s
`<SPITFIRE_PRIV>` / `<SPITFIRE_PUB>`. Each machine keeps its own **private** key;
you exchange the **public** keys.

**`hetzner:/etc/wireguard/wg0.conf`**

```ini
[Interface]
Address    = 10.44.0.1/24
ListenPort = 51820
PrivateKey = <HETZNER_PRIV>

# load / unload the demo's forwarding table together with the tunnel
PostUp   = nft -f /etc/wireguard/forward.nft
PostDown = nft delete table ip ignition_demo

[Peer]   # spitfire
PublicKey  = <SPITFIRE_PUB>
AllowedIPs = 10.44.0.11/32
```

**`hetzner:/etc/wireguard/forward.nft`**

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

**`spitfire:/etc/wireguard/wg0.conf`**

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

`AllowedIPs = 10.44.0.0/24` on `spitfire` means only tunnel-subnet traffic goes
over WireGuard; its normal LAN and internet are untouched.
`PersistentKeepalive` keeps the NAT hole open so `hetzner` can always reach back.

Enable forwarding on `hetzner` (persistently — a `PostUp` alone isn't enough):

```sh
echo 'net.ipv4.ip_forward=1' > /etc/sysctl.d/99-ignition-demo.conf
sysctl --system
```

Bring the tunnel up on both:

```sh
systemctl enable --now wg-quick@wg0
wg show                              # each side should show a recent handshake
```
```sh
ping -c3 10.44.0.11                   # from hetzner
ping -c3 10.44.0.1                    # from spitfire
```

### B5. Host firewall on `hetzner`

Inspect what's already there:

```sh
nft list ruleset | less
nft -a list ruleset | grep -E 'hook (input|forward)'    # look for 'policy drop'
```

**Before changing anything in an existing table:** open a *second* SSH session
and keep it connected, and arm a rollback:

```sh
shutdown -r +10 "firewall rollback"   # cancel with `shutdown -c` once you've re-verified access
```

Then:

- The **`ignition_demo`** NAT table (from B4) is isolated — nothing to reconcile.
- **`ip_forward`** — set in B4. If another file under `/etc/sysctl.d/` sets it
  to `0`, the highest-numbered file wins and `99-` is high; confirm with
  `sysctl net.ipv4.ip_forward` (want `1`).
- **If input policy is `drop`**, append one rule to the *existing* table:

  ```sh
  nft add rule inet <table> <input-chain> udp dport 51820 accept
  ```
- **If forward policy is `drop`**, append the demo flow to the *existing*
  forward chain:

  ```sh
  nft add rule inet <table> <forward-chain> ip daddr 10.44.0.11 tcp dport { 80, 443 } accept
  nft add rule inet <table> <forward-chain> ip saddr 10.44.0.11 ct state established,related accept
  ```

Open a fresh SSH session to confirm you're not locked out, then `shutdown -c`.
Persist whatever you appended into the file `nft` reads at boot
(commonly `/etc/nftables.conf`, or a file in `/etc/nftables.d/`).

### B6. Flip to the public path

Remove the `admin.ignition.theresnolimits.net` line you added to your laptop's
`/etc/hosts` in A3. The name now resolves to `<DEMO_IP>` for real.

```sh
curl -I https://admin.ignition.theresnolimits.net/actuator/health   # from a machine OFF your LAN
```

Full request path:
`client → <DEMO_IP>:443 on hetzner → nft DNAT → WireGuard → spitfire
10.44.0.11:443 → Traefik → ignition-control`.

---

## Stage C — provision a zone and deploy an app

Now that the public path is live, in the platform console
(`https://admin.ignition.theresnolimits.net/`):

1. **Zones → Provision** — enter a slug, e.g. `quantum-badgers`. The scheduler
   places it on `spitfire` (the only node). It stands up Forgejo + a private
   build engine + a runner, creates a `zoneadmin` account, and mints two tokens
   (~1–2 min). For several teams at once use **Roster** and paste a slug list.
2. On the zone's page, copy the **zone token** (the team lead's console
   sign-in — `https://admin.quantum-badgers.ignition.theresnolimits.net/`) and
   the **deploy token** (used by CI).

Each zone automatically requests its own certificate for
`*.quantum-badgers.ignition.theresnolimits.net` +
`*.apps.quantum-badgers.ignition.theresnolimits.net` (those names are two
labels below the apex, past the `*.ignition.theresnolimits.net` cert).

**Deploy an app** — in that zone's Forgejo
(`https://git.quantum-badgers.ignition.theresnolimits.net/`):

1. Create a repo with a `Dockerfile` and
   `.forgejo/workflows/deploy.yml` (copy [`examples/deploy.yml`](examples/deploy.yml)).
2. Set its repo variables / secrets: `REGISTRY`, `CONTROL_URL`, `APP_NAME`,
   `APP_PORT`, `DEPLOY_TOKEN` (the deploy token from step 2), and optionally
   `FORGEJO_TOKEN`.
3. In the **zone console → Repositories**, click **Release**.
   `ignition-control` reads the commits since the last tag, picks the semver
   bump (Conventional Commits: `fix:` → patch, `feat:` → minor,
   `feat!:` / `BREAKING CHANGE:` → major; a dropdown overrides), tags `vX.Y.Z`
   on `main`. The tag builds, pushes to the zone's registry, and deploys.
4. The app comes up at
   `https://<APP_NAME>.apps.quantum-badgers.ignition.theresnolimits.net/`.

A plain `git push` to `main` does **not** deploy — only a release tag does.
After the first deploy, Watchtower on `spitfire` rolls the app forward
automatically whenever that image tag gets a new digest (~60s).

---

## Security notes

- **No authentication anywhere but the consoles.** Every deployed app and every
  zone's Forgejo web UI is open to anyone who resolves the domain. The consoles
  require their bearer token; nothing else does.
- **`IGN_ADMIN_TOKEN` is total control.** It's the bearer / session cookie for
  `admin.ignition.theresnolimits.net`. Treat it like a root password; rotate it
  after the demo (re-run the `ignition-control` compose with a new value).
- **Client IPs are not visible to `spitfire`.** Every request arrives from
  `10.44.0.1` (the `nft` masquerade), so per-client logging or IP allow-listing
  won't work. Acceptable for a demo.
- **`traefik-public` is one flat network on `spitfire`.** A deployed app
  container can reach another zone's Forgejo by IP. The untrusted code is the
  app container — don't run a demo with code you don't trust and expect the
  network layer to isolate zones.
- **When you're done:** delete the `*.ignition.theresnolimits.net` DNS record so
  the names stop resolving, and hand `<DEMO_IP>` back in Robot.

---

## Teardown

```sh
# on spitfire — destroy every zone from the console first (Roster → Teardown), then:
cd ignition
docker compose -f templates/ignition-control-compose.yml down
docker compose -f templates/traefik-core-compose.yml down -v      # -v also drops the ACME cert volume
docker network rm traefik-public

# both machines
systemctl disable --now wg-quick@wg0

# hetzner
rm /etc/wireguard/wg0.conf /etc/wireguard/forward.nft /etc/sysctl.d/99-ignition-demo.conf
# remove the <DEMO_IP> drop-in you added in B2, remove the Robot-firewall row from B3,
# remove any nft rules you appended in B5, then release <DEMO_IP> in Robot.

# DNS: delete the *.ignition.theresnolimits.net record
```

---

## How this differs from the target model

| | this demo | [target model](docs/exposure.md) |
|---|---|---|
| TLS terminates on | `spitfire` | the public controller |
| `hetzner` runs | `nft` DNAT only | edge Traefik + SSO gateway + `ignition-control` |
| node reachability | `spitfire` holds `:443` behind the forward | node Traefik internal-only `:80`, edge routes by `Host` |
| authentication | none | one `forward-auth` SSO gateway for all browser traffic |
| control-plane location | on the node | on the controller |
| compose-template changes | none | task 4 |

Moving to the target model later reuses everything here: same DNS record, same
certificates, same zones. You'd relocate `ignition-control` and the edge Traefik
onto `hetzner`, make `spitfire`'s Traefik internal-only, and add the
`forward-auth` container.

### Alternative to the `nft` forwarder

If you'd rather not touch nftables at all, an SSH reverse tunnel from `spitfire`
does the same job (needs `GatewayPorts clientspecified` in
`hetzner:/etc/ssh/sshd_config`):

```sh
# on spitfire, ideally as a systemd unit using autossh
ssh -N -R <DEMO_IP>:80:localhost:80 -R <DEMO_IP>:443:localhost:443 <you>@<PUBLIC_IP>
```

Binding to `<DEMO_IP>` keeps it off Apache's `<PUBLIC_IP>`. Drop the `PostUp` /
`PostDown` lines from `hetzner`'s `wg0.conf` if you use this. WireGuard + `nft`
is steadier for an all-day demo; the SSH tunnel is faster to stand up for an
hour.
