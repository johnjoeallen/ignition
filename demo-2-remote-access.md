# Demo, part 2 — remote access via a public box

**Prerequisite:** [part 1](demo-1-spitfire.md) is done — Ignition runs on
`spitfire` and its platform console works from your LAN with a real certificate.

This part makes the demo reachable from the internet by routing one spare
public IP through a box you already run, then provisions a team and deploys an
app.

- **`hetzner`** — a public root server you already run (web on `:80`/`:443`,
  mail on `:143`, SSH). For the demo it does exactly two things: it is the
  **WireGuard endpoint** `spitfire` dials out to, and it **forwards** one spare
  public IP's `:80`/`:443` down that tunnel to `spitfire`. It terminates no TLS,
  holds no Ignition config, and **none of its existing services are touched.**

```
   Apache's existing sites              *.ignition.theresnolimits.net
   (internet → <PUBLIC_IP>)             (internet → <DEMO_IP>)
            │  :80/:443                          │  :80/:443
 ┌──────────▼────────────────────────────────────▼────────┐
 │  hetzner  (root server, unchanged services)            │
 │   <PUBLIC_IP>  →  Apache / Dovecot / sshd  (untouched) │
 │   <DEMO_IP>    →  nft DNAT :80/:443  ─┐                 │
 │   WireGuard listener :51820  10.44.0.1 │                │
 └───────────────────────────────────────┼───────────────┘
                                         │  WireGuard tunnel
                     spitfire dials OUT to hetzner:51820
                                         │
                     ┌───────────────────▼──────┐
                     │  spitfire   10.44.0.11    │  (part 1 — unchanged)
                     └──────────────────────────┘
```

> **Why not put the control plane on `hetzner` instead?** The
> [target architecture](docs/exposure.md) does — the public box runs the edge
> Traefik and an SSO gateway. Wiring that into the compose templates is
> unfinished (task 4). Until then, `spitfire` runs everything and `hetzner` is
> a dumb layer-4 forwarder.

---

## The pieces, explained

### The two IPs on `hetzner`

- **`<PUBLIC_IP>`** — the address `hetzner` already has. Apache, Dovecot and
  sshd listen on it. **Nothing here changes that.**
- **`<DEMO_IP>`** — a *second*, separate public IPv4 you add to `hetzner` just
  for the demo (Hetzner calls it an "additional IP", ordered in Robot). Its own
  address is what lets the forwarding rule be "anything for `<DEMO_IP>` goes to
  `spitfire`" without ever matching the real services. Hand it back when done.

### The DNS wildcard record

Now you create **one** record: `*.ignition.theresnolimits.net → <DEMO_IP>`.

A `*` record is a catch-all: it answers for **any** name under
`ignition.theresnolimits.net` at **any depth** (standard DNS, RFC 4592). So
`admin.ignition.theresnolimits.net`,
`git.quantum-badgers.ignition.theresnolimits.net`, and
`paywise.apps.quantum-badgers.ignition.theresnolimits.net` all resolve from
that single record — which is why **provisioning a team never touches DNS.**

### WireGuard

A small, modern VPN. It builds one encrypted link between `hetzner`
(`10.44.0.1`) and `spitfire` (`10.44.0.11`). `spitfire` **dials out** to
`hetzner:51820`, so your router needs no configuration. Once up, `hetzner` can
reach `spitfire` even though `spitfire` accepts nothing inbound.

### The `nft` forward

`nft` (nftables) is Linux's built-in packet filter / NAT. The demo adds a tiny,
self-contained table on `hetzner`: *"a TCP connection arriving for `<DEMO_IP>`
on port 80 or 443 → rewrite its destination to `10.44.0.11` and send it down
the tunnel."* It's a separate table (`ignition_demo`), so it cannot interfere
with any firewall `hetzner` already runs.

---

## What you'll fill in

| placeholder | what it is | example |
|---|---|---|
| `<PUBLIC_IP>` | `hetzner`'s existing address — unchanged | `203.0.113.10` |
| `<DEMO_IP>` | the extra public IPv4 you add to `hetzner` | `203.0.113.55` |
| `<HETZNER_PRIV>` / `<HETZNER_PUB>` | WireGuard keypair generated on `hetzner` | (Step 5) |
| `<SPITFIRE_PRIV>` / `<SPITFIRE_PUB>` | WireGuard keypair generated on `spitfire` | (Step 5) |

---

## Why this is safe for the existing services

`hetzner` already serves `:80`, `:443`, `:143` and SSH on `<PUBLIC_IP>`. Nothing
below edits an existing service, IP binding, or firewall rule, because:

- the demo lives entirely on the **separate** `<DEMO_IP>`;
- the forwarding is its **own** nft table (`ignition_demo`) — nft tables are
  independent, so adding or removing one cannot change a rule in another;
- that table only matches `ip daddr <DEMO_IP> tcp dport {80,443}` — a packet to
  `<PUBLIC_IP>:80` / `:443` / `:143` / `:22` never matches it.

The **one** unavoidable addition is an inbound `udp/51820` accept for WireGuard,
and only if `hetzner`'s host firewall defaults to dropping input.

---

## Step 1 — the DNS wildcard record

At Joker (the DNS host for `theresnolimits.net`):

```
*.ignition.theresnolimits.net.   A   <DEMO_IP>
ignition.theresnolimits.net.     A   <DEMO_IP>     # optional: only if you want the bare apex to resolve
```

The certificate from part 1 is unaffected — it was issued via DNS-01 and
doesn't care where the `A` record points.

---

## Step 2 — add `<DEMO_IP>` in Robot

Order a **single additional IPv4** for the server (Robot → *IPs* →
*Order additional IP*). Hetzner routes it to the server's main interface — no
separate MAC address is needed to use it on the host. Robot tells you whether
it's in the **same subnet** as `<PUBLIC_IP>` (usual — bind as `/32`) or a
**separate subnet** (it also gives a gateway; use the `pointopoint` form from
Hetzner's "Additional IP addresses" doc).

---

## Step 3 — bind it on Debian

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
ip addr add <DEMO_IP>/32 dev <iface>    # apply now, main iface not restarted
```

Verify — from another host:

```sh
ping <DEMO_IP>
curl -I http://<DEMO_IP>/               # still reaches Apache; nothing forwards yet
```

Re-check your real site and IMAP. Everything should be exactly as before.

---

## Step 4 — Hetzner Robot firewall (only if you use it)

Hetzner offers a **stateless** hardware firewall (Robot → *Firewall*). If it is
**not** active on this server, leave it off — do not enable it now.

If it **is** active, its template already permits your `:80` / `:443` / `:143` /
SSH. **Add one row, keep every existing row:**

| version | protocol | dst port | action |
|---|---|---|---|
| IPv4 | `udp` | `51820` | accept |

It filters all of the server's IPs, so the existing `:80` / `:443` rows already
cover `<DEMO_IP>`. (Being stateless it also needs the "TCP established" accept
row — which it has if SSH works today.)

---

## Step 5 — WireGuard on both machines

Install: `apt install wireguard` on both.

Generate a keypair on **each** machine:

```sh
wg genkey | tee privatekey | wg pubkey > publickey
```

Call `hetzner`'s pair `<HETZNER_PRIV>` / `<HETZNER_PUB>` and `spitfire`'s
`<SPITFIRE_PRIV>` / `<SPITFIRE_PUB>`. Each machine keeps its own **private** key;
exchange the **public** keys.

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

---

## Step 6 — host firewall on `hetzner`

Inspect what's already there:

```sh
nft list ruleset | less
nft -a list ruleset | grep -E 'hook (input|forward)'    # look for 'policy drop'
```

**Before changing anything in an existing table:** open a *second* SSH session
and keep it connected, and arm a rollback:

```sh
shutdown -r +10 "firewall rollback"   # cancel with `shutdown -c` once access is re-verified
```

Then:

- The **`ignition_demo`** NAT table (from Step 5) is isolated — nothing to
  reconcile.
- **`ip_forward`** — set in Step 5. If another file under `/etc/sysctl.d/` sets
  it to `0`, the highest-numbered file wins and `99-` is high; confirm with
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
Persist whatever you appended into the file `nft` reads at boot (commonly
`/etc/nftables.conf`, or a file in `/etc/nftables.d/`).

---

## Step 7 — flip to the public path

Remove the `admin.ignition.theresnolimits.net` line you added to your laptop's
`/etc/hosts` in part 1. The name now resolves to `<DEMO_IP>` for real.

```sh
curl -I https://admin.ignition.theresnolimits.net/actuator/health   # from a machine OFF your LAN
```

Full request path:
`client → <DEMO_IP>:443 on hetzner → nft DNAT → WireGuard → spitfire
10.44.0.11:443 → Traefik → ignition-control`.

---

## Step 8 — provision a zone and deploy an app

In the platform console (`https://admin.ignition.theresnolimits.net/`):

1. **Zones → Provision** — enter a slug, e.g. `quantum-badgers`. The scheduler
   places it on `spitfire` (the only node). It stands up Forgejo + a private
   build engine + a runner, creates a `zoneadmin` account, and mints two tokens
   (~1–2 min). For several teams at once use **Roster** and paste a slug list.
2. On the zone's page, copy the **zone token** (the team lead's console
   sign-in — `https://admin.quantum-badgers.ignition.theresnolimits.net/`) and
   the **deploy token** (used by CI).

Each zone automatically requests its own certificate for
`*.quantum-badgers.ignition.theresnolimits.net` +
`*.apps.quantum-badgers.ignition.theresnolimits.net` (two labels below the
apex, past the `*.ignition.theresnolimits.net` cert).

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
After the first deploy, Watchtower on `spitfire` rolls the app forward whenever
that image tag gets a new digest (~60s).

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
  won't work.
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
# remove the <DEMO_IP> drop-in from Step 3, the Robot-firewall row from Step 4,
# any nft rules you appended in Step 6, then release <DEMO_IP> in Robot.

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

An SSH reverse tunnel from `spitfire` does the same job (needs
`GatewayPorts clientspecified` in `hetzner:/etc/ssh/sshd_config`):

```sh
# on spitfire, ideally as a systemd unit using autossh
ssh -N -R <DEMO_IP>:80:localhost:80 -R <DEMO_IP>:443:localhost:443 <you>@<PUBLIC_IP>
```

Binding to `<DEMO_IP>` keeps it off Apache's `<PUBLIC_IP>`. Drop the `PostUp` /
`PostDown` lines from `hetzner`'s `wg0.conf` if you use this. WireGuard + `nft`
is steadier for an all-day demo; the SSH tunnel is faster to stand up for an
hour.
