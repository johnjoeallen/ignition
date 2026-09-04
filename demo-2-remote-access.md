# Demo, part 2 — remote access via a public box (one shared IPv4)

**Prerequisite:** [part 1](demo-1-spitfire.md) is done — Ignition runs on
`spitfire` and its platform console works from your LAN with a real certificate.

This part makes the demo reachable from the internet through a root server you
already run, **without a second IP and without IPv6** (which corporate networks
often can't reach). The trick: dedicate the whole `ignition.classesarecode.net`
domain to the demo and split traffic on `hetzner` by **TLS SNI** — the hostname
the client asks for, visible in the TLS handshake without decrypting it.

- **`hetzner`** — one public IPv4 (`<PUBLIC_IP>`), already serving web on
  `:80`/`:443` (Apache) plus mail and SSH. For the demo it runs a small **SNI
  front proxy** on that IP: connections for `*.ignition.classesarecode.net` go
  down a WireGuard tunnel to `spitfire`; everything else goes to Apache on
  loopback. **Mail and SSH are untouched; Apache needs a one-line port change,
  no vhost edits.**

```
                       internet  →  <PUBLIC_IP>:443
                                │
                ┌───────────────▼───────────────┐
                │   hetzner :  nginx SNI router  │
                └───────┬───────────────┬────────┘
   SNI ends in          │               │          SNI = anything else
   .ignition.classesarecode.net         │
   (raw TLS over WireGuard)             ▼
                        │      Apache  127.0.0.1:443
                        ▼      (your existing sites — unchanged, loopback-only)
             spitfire  10.44.0.11:443
             (part 1 — unchanged; terminates its own TLS)
```

> **Why not put the control plane on `hetzner` instead?** The
> [target architecture](docs/exposure.md) does — the public box runs the edge
> Traefik and an SSO gateway. Wiring that into the compose templates is
> unfinished (task 4). Until then, `spitfire` runs everything and `hetzner`
> only shuttles bytes.

---

## The pieces, explained

### A dedicated domain + SNI routing

Every demo hostname is under `ignition.classesarecode.net`; none of Apache's
sites are. So `hetzner` can decide where a connection goes purely from the
**SNI** — the server name in the TLS `ClientHello`, sent in the clear before any
encryption. `nginx`'s `ssl_preread` reads it **without terminating TLS**:
`spitfire` still presents its own Let's Encrypt certificate end-to-end, and
Apache still presents its own for its own domains. `hetzner` decrypts nothing.

Corporate networks pass SNI fine (it's how every HTTPS site is reached). Only
Encrypted Client Hello would hide it, and that isn't in play here.

### WireGuard

A small, modern VPN. It builds one encrypted link between `hetzner`
(`10.44.0.1`) and `spitfire` (`10.44.0.11`). `spitfire` **dials out** to
`hetzner:51820`, so your router needs no configuration. Once up, `hetzner` can
reach `spitfire` even though `spitfire` accepts nothing inbound.

### The front proxy

`nginx` binds **`<PUBLIC_IP>:443`** (the public address specifically). Apache
moves to **`127.0.0.1:443`** (same port, loopback only) — so its
`<VirtualHost *:443>` blocks still match and **no vhost file changes**. nginx's
`stream` module forwards each connection by SNI: demo names over WireGuard to
`spitfire`, everything else to `127.0.0.1:443`.

### With many existing Apache SSL sites

This is the case it's built for. `ssl_preread` **does not decrypt** — it reads
the SNI and hands the raw TLS stream on. So for every one of your existing
sites, **Apache still terminates TLS itself**: its own per-vhost certificate
selection, HTTP/2, OCSP stapling, client-cert config — all unchanged. nginx has
a single `default` rule that covers *all* of them, however many; you never list
a site in nginx.

Two things to know:

- **Client IP.** Apache will log `127.0.0.1` for HTTPS hits unless you turn on
  PROXY protocol (Step 4 note). Matters if you use `Require ip`, mod_security,
  fail2ban on the Apache log, or per-IP rate limits.
- **Cert renewal keeps working.** HTTP-01 (webroot / `--webroot`, `--standalone`
  on `:80`) is untouched — Apache keeps `:80`. TLS-ALPN-01 (`certbot --apache`)
  still works too: the validation request carries the site's SNI, so nginx
  routes it to Apache, which answers the ALPN challenge. DNS-01, obviously fine.

---

## What you'll fill in

| placeholder | what it is | example |
|---|---|---|
| `<PUBLIC_IP>` | `hetzner`'s existing public IPv4 | `203.0.113.10` |
| `<IFACE>` | `hetzner`'s network interface | `eth0` / `enp0s31f6` |
| `<HETZNER_PRIV>` / `<HETZNER_PUB>` | WireGuard keypair generated on `hetzner` | (Step 2) |
| `<SPITFIRE_PRIV>` / `<SPITFIRE_PUB>` | WireGuard keypair generated on `spitfire` | (Step 2) |

> **Or generate everything.** If you used [`demo/`](demo/README.md) in part 1,
> `demo/out/` already holds `hetzner-wg0.conf`, `spitfire-wg0.conf`, and
> `hetzner-nginx-stream.conf` — filled in, keys and all. This guide explains
> what each one does; `demo/out/INSTALL.txt` says where to put them.

---

## What changes on `hetzner`, and what doesn't

**Changes:**

- Apache's `Listen 443` → `Listen 127.0.0.1:443` (one line in `ports.conf`).
- `nginx` installed and given `<PUBLIC_IP>:443`.
- One inbound `udp/51820` firewall rule (WireGuard), *only if* the host firewall
  drops input by default.

**Not touched:** every Apache vhost file, `:80`, all mail ports (`25` `110`
`143` `465` `587` `993` `995`), SSH, and Apache's own certificates.

**`:80` stays entirely with Apache** — nginx only takes `:443`. So **certbot
HTTP-01 renewals keep working exactly as now**. The demo doesn't need `:80`
(every Ignition URL is `https`; `spitfire`'s certs come from DNS-01), it just
won't answer plain `http://` for its own names — fine for a demo. Only touch
`:80` if you deliberately want that redirect, and then you *must* keep ACME
challenges flowing to Apache (see the note at the end).

---

## Step 1 — the DNS wildcard record

Wherever `ignition.classesarecode.net` is served — Joker if you used part 1
Option A, deSEC/Cloudflare if Option B:

```
*.ignition.classesarecode.net.   A   <PUBLIC_IP>
ignition.classesarecode.net.     A   <PUBLIC_IP>     # optional: the bare apex
```

One record covers every depth (`admin.…`, `git.<slug>.…`,
`x.apps.<slug>.…`) — provisioning a zone adds no DNS. The certificate from
part 1 is unaffected (DNS-01 doesn't care where the `A` record points).

---

## Step 2 — WireGuard on both machines

`apt install wireguard` on both. Generate a keypair on **each**:

```sh
wg genkey | tee privatekey | wg pubkey > publickey
```

Each machine keeps its own **private** key; exchange the **public** keys.

**`hetzner:/etc/wireguard/wg0.conf`**

```ini
[Interface]
Address    = 10.44.0.1/24
ListenPort = 51820
PrivateKey = <HETZNER_PRIV>

[Peer]   # spitfire
PublicKey  = <SPITFIRE_PUB>
AllowedIPs = 10.44.0.11/32
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

`AllowedIPs = 10.44.0.0/24` on `spitfire` keeps only tunnel traffic on
WireGuard. No IP forwarding or NAT is needed on `hetzner` — nginx does the
forwarding at the application layer.

```sh
systemctl enable --now wg-quick@wg0     # both machines
wg show                                 # a recent handshake on each side
ping -c3 10.44.0.11                      # from hetzner
```

---

## Step 3 — move Apache to loopback

Keep a second SSH session open. In `hetzner`'s Apache config
(`/etc/apache2/ports.conf` on Debian):

```apache
# was: Listen 443
Listen 127.0.0.1:443
```

Leave `Listen 80` alone unless you also want nginx to handle `:80` (see the note
at the end). Reload and confirm your existing sites still serve — bypassing
nginx, straight to Apache:

```sh
apachectl configtest && systemctl reload apache2
curl -kI --resolve your-existing-site.com:443:127.0.0.1 https://your-existing-site.com/
```

At this point your sites are only reachable from `hetzner` itself — nginx
(next step) puts them back on the internet.

---

## Step 4 — the nginx SNI proxy

```sh
apt install nginx
```

Debian's `nginx.conf` only wires up the `http {}` block, so add the `stream {}`
router at the **top level** of `/etc/nginx/nginx.conf` (a sibling of
`http { … }`, not inside it):

```nginx
stream {
    map $ssl_preread_server_name $demo_backend {
        ~\.ignition\.classesarecode\.net$   10.44.0.11:443;   # demo → spitfire over WireGuard
        default                             127.0.0.1:443;    # all your Apache sites → Apache
    }

    server {
        listen      <PUBLIC_IP>:443;
        listen      [::]:443;          # drop this line if hetzner has no usable IPv6
        proxy_pass  $demo_backend;
        ssl_preread on;                # read SNI, do NOT terminate TLS
        proxy_timeout 10m;             # long-poll consoles, docker pushes
    }
}
```

The `default` line is the whole story for your existing sites — one rule, any
number of vhosts, nothing to enumerate.

Make sure nothing in `http {}` also binds `:443` (Debian's default site only
binds `:80`; if you enabled an HTTPS default server, disable it —
`rm /etc/nginx/sites-enabled/default` or edit it).

```sh
nginx -t && systemctl enable --now nginx
```

### Keeping the real client IP for Apache (optional)

Without this, Apache logs every HTTPS hit as `127.0.0.1`. To pass the real
address, add `proxy_protocol` to the stream `server` and teach Apache to read
it:

```nginx
    server {
        listen      <PUBLIC_IP>:443;
        proxy_pass  $demo_backend;
        ssl_preread on;
        proxy_protocol on;                       # prepend the PROXY header
    }
```

`hetzner` Apache (needs 2.4.31+): enable `remoteip` and turn on PROXY-protocol
parsing globally (so it covers every `:443` vhost), trusting only the loopback:

```sh
a2enmod remoteip
```
```apache
# /etc/apache2/conf-available/proxy-protocol.conf  (a2enconf it, then reload)
RemoteIPProxyProtocol On
```

Now every connection to `127.0.0.1:443` must carry the PROXY header — which
nginx always sends. A direct local test needs `curl --haproxy-protocol`.

Skip it if your sites don't care about client IPs. It has no effect on the demo
path — `spitfire` always sees the tunnel address either way.

Verify from a machine **off your LAN**:

```sh
curl -I https://admin.ignition.classesarecode.net/actuator/health   # → spitfire
curl -kI https://your-existing-site.com/                            # → Apache, unchanged
```

Path for the demo: `client → <PUBLIC_IP>:443 (nginx, SNI) → WireGuard →
spitfire 10.44.0.11:443 → Traefik → ignition-control`.

---

## Step 5 — firewall

`:443` is already open (Apache used it). The only possible addition is
**`udp/51820`** for WireGuard.

- **Hetzner Robot firewall** (if active): add one row — IPv4 / `udp` / dst port
  `51820` / accept — and keep every existing row.
- **Host `nft`/`iptables`** (if it drops input by default): append **one** rule
  to the *existing* table, with a second SSH session open and a rollback armed:

  ```sh
  shutdown -r +10 "firewall rollback"        # cancel with `shutdown -c` after re-checking access
  nft add rule inet <table> <input-chain> udp dport 51820 accept
  # confirm a fresh SSH login works, then:
  shutdown -c
  ```

  Persist it into whatever file `nft` reads at boot.

If there's no host firewall and no Robot firewall, WireGuard's outbound-dialed
tunnel needs nothing.

---

## Step 6 — flip to the public path

Remove the `admin.ignition.classesarecode.net` line from your laptop's
`/etc/hosts` (added in part 1). The name now resolves to `<PUBLIC_IP>` and
routes through nginx for real.

---

## Step 7 — provision a zone and deploy an app

In the platform console (`https://admin.ignition.classesarecode.net/`):

1. **Zones → Provision** — a slug, e.g. `quantum-badgers`. The scheduler places
   it on `spitfire` (the only node): Forgejo + a private build engine + a
   runner + a `ignition-bot` account + two tokens (~1–2 min). Bulk: **Roster**.
2. Copy the zone's **zone token** (team-lead console sign-in at
   `https://admin.quantum-badgers.ignition.classesarecode.net/`) and **deploy
   token** (CI).

Each zone auto-requests its own `*.quantum-badgers.ignition.classesarecode.net`
+ `*.apps.quantum-badgers.ignition.classesarecode.net` cert (two labels below
the apex).

**Deploy an app** — in that zone's Forgejo
(`https://git.quantum-badgers.ignition.classesarecode.net/`):

1. A repo with a `Dockerfile` and `.forgejo/workflows/deploy.yml`
   (copy [`examples/deploy.yml`](examples/deploy.yml)).
2. Repo vars / secrets: `REGISTRY`, `CONTROL_URL`, `APP_NAME`, `APP_PORT`,
   `DEPLOY_TOKEN`, optionally `FORGEJO_TOKEN`.
3. **Zone console → Repositories → Release** — `ignition-control` reads the
   commits since the last tag, picks the semver bump (Conventional Commits;
   dropdown overrides), tags `vX.Y.Z` on `main`; the tag builds, pushes, deploys.
4. Live at `https://<APP_NAME>.apps.quantum-badgers.ignition.classesarecode.net/`.

A plain `git push` to `main` does **not** deploy — only a release tag.
Watchtower on `spitfire` then rolls the app forward on any new digest for that
tag (~60s).

---

## Security notes

- **No authentication anywhere but the consoles.** Every deployed app and every
  zone's Forgejo web UI is open to anyone who resolves the domain.
- **`IGN_ADMIN_TOKEN` is total control** — the bearer / session cookie for the
  platform console. Rotate it after the demo.
- **Client IPs:** `spitfire` sees every request from `10.44.0.1` (the tunnel).
  Apache, reached via `ssl_preread`, sees `127.0.0.1` — if that matters for your
  real sites' logs, add `proxy_protocol` to the nginx `proxy_pass` **and**
  `RemoteIPProxyProtocol On` (Apache ≥ 2.4.31) on the loopback listener.
- **`traefik-public` is one flat network on `spitfire`** — a deployed app can
  reach another zone's Forgejo by IP. Don't demo with untrusted app code.
- **When done:** delete the `*.ignition.classesarecode.net` DNS record.

---

## Teardown

```sh
# spitfire — destroy every zone from the console first (Roster → Teardown), then:
cd ignition
docker compose --project-directory . -f templates/ignition-control-compose.yml down
docker compose --project-directory . -f templates/traefik-core-compose.yml down -v
docker network rm traefik-public
systemctl disable --now wg-quick@wg0

# hetzner
systemctl disable --now nginx wg-quick@wg0
apt purge nginx                                   # or keep it
sed -i 's/^Listen 127.0.0.1:443/Listen 443/' /etc/apache2/ports.conf
apachectl configtest && systemctl reload apache2
rm /etc/wireguard/wg0.conf
# remove the udp/51820 firewall rule you added

# DNS: delete the *.ignition.classesarecode.net record
```

---

## How this differs from the target model

| | this demo | [target model](docs/exposure.md) |
|---|---|---|
| TLS terminates on | `spitfire` | the public controller |
| `hetzner` runs | nginx SNI passthrough | edge Traefik + SSO gateway + `ignition-control` |
| node reachability | `spitfire` holds `:443` behind the proxy | node Traefik internal-only `:80`, edge routes by `Host` |
| authentication | none | one `forward-auth` SSO gateway |
| control-plane location | on the node | on the controller |
| compose-template changes | none | task 4 |

Moving to the target model later reuses everything here: same DNS record, same
certificates, same zones.

---

## Handling `http://` too (optional)

The minimal setup leaves Apache on `:80` and doesn't answer `http://` for demo
names — which is fine (Ignition is https-only, `spitfire` uses DNS-01).

If you want the redirect, nginx has to take `:80` — and then **certbot HTTP-01
for your Apache sites would break** unless you keep passing the challenge
through. Move Apache with `Listen 127.0.0.1:80`, then in nginx `http {}` on
`<PUBLIC_IP>:80`:

```nginx
server {
    listen <PUBLIC_IP>:80;
    listen [::]:80;

    # let ACME HTTP-01 for the Apache sites through, untouched
    location ^~ /.well-known/acme-challenge/ {
        proxy_pass http://127.0.0.1:80;
        proxy_set_header Host $host;
    }

    # demo names → force https
    if ($host ~ \.ignition\.classesarecode\.net$) { return 301 https://$host$request_uri; }

    # everything else → Apache
    location / {
        proxy_pass http://127.0.0.1:80;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

Simpler if you can: switch your Apache certs to DNS-01 or `--webroot` behind
this proxy, or just don't bother with the `:80` redirect.

---

## Alternative — a second dedicated IP (no Apache change)

If you'd rather not put nginx in front of Apache, order a **second IPv4** for
the server in Robot, bind it as `<DEMO_IP>/32` on `<IFACE>` *alongside* the main
address, point `*.ignition.classesarecode.net` at `<DEMO_IP>`, and forward just
that IP's `:443` to `spitfire` with an isolated nftables DNAT table
(`ip daddr <DEMO_IP> tcp dport 443 dnat to 10.44.0.11`, plus `masquerade`
`oifname "wg0"`, plus `net.ipv4.ip_forward=1`). Apache, mail and SSH keep
`<PUBLIC_IP>` and nothing about them changes. Costs a small monthly fee for the
IP; needs no nginx and no Apache edit.
