# Exposure & access

The default — direct inbound on the node, wildcard certs from an ACME CA via
the DNS-01 challenge — is one option among several. A cluster picks an
**exposure profile** at install, and individual zones or apps can narrow it.

**No third-party services.** Everything here is self-hosted: a relay host *you*
run, WireGuard, your own ACME / PKI, your own identity provider. No Cloudflare,
Tailscale, ngrok, or any hosted tunnel.

Three independent knobs:

1. **Reachability** — how a request gets to the node: direct **inbound**, or an
   outbound **reverse tunnel** to a relay host you control (no inbound ports on
   the cluster).
2. **TLS** — a publicly-trusted cert from an ACME CA, a cert from your own
   internal CA, or (last resort) plain HTTP.
3. **Authorization** — open to anyone who can reach it, gated by **SSO** against
   your identity provider, or reachable only over a private network (the tunnel
   *is* the boundary).

| Profile | Reachability | TLS | Typical audience |
|---|---|---|---|
| `public` (default) | inbound `:80/:443` on the node | ACME DNS-01 wildcard | the internet |
| `public-http01` | inbound `:80/:443`, no DNS API | ACME HTTP-01, per host | the internet |
| `relay` | outbound tunnel to your relay host | ACME on the relay, or your internal CA | wherever the relay lives — a VPS (internet) or a DMZ box (corp) |
| `internal-ca` | inbound, private network only | your internal CA / ACME | corp devices |
| `http-only` | inbound | none | closed demo network only |

The `sso` layer is added on top of **any** of these.

---

## The Ignition box is multi-homed

Whatever the topology, the **internal Ignition network is always isolated**:
`traefik-public`, every `zone-<slug>` network, and each zone's DinD engine are
Docker bridge networks that live on the box and have **no route to the
corporate network or the internet inbound**. Corp users never touch a zone's
Forgejo, runner, or build engine — only the app routers Traefik publishes.

Exposure is therefore a question of **which interface Traefik binds `:80/:443`
to**, and how a request reaches it. The box typically has two or more
attachments:

- **corporate DMZ** — so people on the corp network can reach the demos
  (SSO + SSL mandatory here);
- **the internal Ignition network** — the isolated per-zone stacks (never
  routable from outside);
- optionally **a direct internet link** — a separate NIC / uplink for external
  guests, used when corp can't accept inbound or run a tunnel to the box.

### Topologies

| # | Box is attached to | App traffic reaches it via | Profile | Notes |
|---|---|---|---|---|
| **A** | corp DMZ + internal | Traefik on the **DMZ interface** | `public` (internal-CA or public ACME) **+ `sso`** | The common corporate case. `IGN_EXPOSE_ADDR` binds `:443` to the DMZ IP only. Certs from the corp internal CA (`step-ca`) or public ACME if the DMZ names are delegatable. |
| **B** | corp DMZ + internal + a direct internet link | Traefik on **both** the DMZ and the public interface | `public` + `sso` for corp names, plus public ACME on the internet interface | Two entrypoints / two sets of routers; external guests hit the public NIC, corp hits the DMZ NIC. Split the router hostnames or duplicate them per interface. |
| **C** | internet only (no corp link at all) | Traefik on the **public interface** | `public` (public ACME) **± `sso`** | The box is just on the internet. SSO optional — a self-hosted Keycloak with invited accounts if you want to restrict to named guests. |
| **D** | a local switch only (air-gapped) | Traefik on the **LAN interface**, laptops in the room | `internal-ca` or `http-only` | No remote demo. Certs from a `step-ca` you run on the box, or plain HTTP. |
| **E** | corp DMZ + internal, but corp won't route inbound to the DMZ | outbound **tunnel** from the box to a relay | `relay` (+ `sso`) | Box dials out to a relay (DMZ box or VPS); see below. |

`IGN_EXPOSE_ADDR` (host IP or `0.0.0.0`) controls which interface the Traefik
ports bind to; `ignition-control` records the resulting scheme + host so the
console and `/info` show the URL the audience will actually use.

---

## 1. Public inbound (`public`, `public-http01`)

The node's Traefik owns `:80` / `:443`; requests arrive directly.

- **DNS**: `git.<slug>` / `*.apps.<slug>` A-records → the node running the zone;
  `admin.<slug>` and `admin.<BASE_DOMAIN>` → the control host.
- **Certs — DNS-01 wildcard (`public`, preferred)**: Traefik answers the ACME
  challenge by writing a TXT record through the DNS provider's API, so it gets
  `*.<slug>.<BASE_DOMAIN>` + `*.apps.<slug>.<BASE_DOMAIN>` with **zero inbound
  reachability during issuance**. The ACME CA can be Let's Encrypt or your own
  ACME endpoint (`step-ca`) — set `ACME_CA_SERVER`. This is what
  `traefik-core-compose.yml` does today.
- **Certs — HTTP-01 (`public-http01`)**: no DNS API → per-hostname HTTP-01. The
  CA connects to `:80` for each `<app>.apps.<slug>.<BASE_DOMAIN>` as it first
  appears. Needs `:80` open and a real A-record per app (no wildcard). Slower,
  rate-limited, first-request handshake delay per new app.

Use when the cluster has a routable IP and you control the DNS zone.

## 2. Reverse tunnel to your relay (`relay`)

The cluster has **no inbound** (home connection, NAT, a locked-down VLAN). A
lightweight tunnel client on the control host dials **out** to a **relay host
you run** — a small public VPS for an internet audience, or a box in the
corporate DMZ for an internal one. The relay has the public/routable address;
the cluster never opens a port.

Pick one transport (all OSS, all self-hosted):

- **`rathole`** or **`frp`** — a purpose-built reverse-tunnel pair: `…-server`
  on the relay, `…-client` on the control host, one shared token. Forwards the
  relay's `:443` to `traefik:443` (or `:80`) on `traefik-public`.
- **WireGuard** — the control host and the relay are WireGuard peers; the relay
  runs nginx / HAProxy / Traefik and `proxy_pass`es over the tunnel to the
  cluster's Traefik. More moving parts, but it's just WireGuard.
- **`ssh -R`** — `ssh -R 443:traefik:443 relay` from the control host (with
  `autossh` / a systemd unit). Zero extra software on the relay. Fine for one
  or two events.

- **DNS**: `*.apps.<slug>.<BASE_DOMAIN>` (and the `admin.*` / `git.*` hosts) →
  the **relay's** address. For a corp audience, put those records only in the
  corp resolver (split-horizon) so the names don't resolve outside.
- **TLS**: terminate on the **relay**. Either run Traefik/Caddy there with ACME
  (DNS-01 against your zone, or HTTP-01 — the relay *does* have inbound), or
  hand it a cert from your internal CA. The cluster's Traefik can then speak
  plain HTTP over the tunnel, or you pass TLS straight through (SNI routing) and
  let the cluster terminate — either works.

`ignition-control` records the effective scheme + host so the console and
`/info` show the relay URL, not the node's.

## 3. Inbound on a private network, internal CA (`internal-ca`)

Everything stays inside the perimeter: the cluster is reachable from the corp
network (directly, or via a subnet route / WireGuard you manage), and certs
come from **your own CA**.

- **Certs**: point Traefik's ACME resolver at a **self-hosted ACME** —
  `step-ca` (smallstep) is a single Go binary; or an ADCS instance with an ACME
  responder. Managed devices already trust the chain, so browsers are happy on
  any hostname with no internet involved. Set `ACME_CA_SERVER` +
  `ACME_CA_ROOT` (the resolver's trusted root).
- **DNS**: corp resolvers answer `git.<slug>` / `*.apps.<slug>` /
  `admin.<slug>` with the node / control-host addresses; nothing is published
  publicly.
- No inbound from the internet at all.

## 4. Plain HTTP (`http-only`)

When there is genuinely no trusted-cert path and the audience is on a closed
network you control:

- Traefik serves the `web` entrypoint only — no HTTPS redirect, no cert
  resolver.
- `ignition-control` marks every app URL `http://…` in the console and on
  `/info`, and **refuses to enable `sso`** (an unauthenticated redirect over
  plain HTTP leaks the session).
- Never for anything carrying credentials or judging feedback.

## TLS options, summarised

| Option | Needs | Gives |
|---|---|---|
| **ACME DNS-01 wildcard** | DNS-provider API token + an ACME CA (public or your own) | one `*.<slug>` + `*.apps.<slug>` cert per zone, issued with zero inbound |
| **ACME HTTP-01 per host** | `:80` reachable by the CA, an A-record per app | a cert per app, on first request |
| **Internal ACME (`step-ca`)** | one self-hosted binary reachable from the node | trusted-on-managed-devices certs, any hostname, no internet |
| **Terminate on the relay** | Traefik/Caddy + ACME (or an internal-CA cert) on the relay host | trusted cert at the relay; cluster speaks plain HTTP or passes TLS through |
| **none** | — | plain HTTP only |

## SSO

Independent of reachability, but **mandatory whenever the box is on the
corporate DMZ** (topologies A / B / E) — the DMZ interface is reachable by the
whole corp network, so app routers there must be gated. Optional for a purely
public box (C) if the guest list is closed.

An identity proxy sits in front of the app routers; unauthenticated requests
bounce to your IdP.

- **Traefik `forwardAuth` middleware → `oauth2-proxy`**, or **Authelia**
  (self-contained: OIDC/LDAP, 2FA, access-control rules), configured against
  **your** OIDC provider — **Keycloak** (self-hosted), or an existing corp
  Entra ID / Okta / LDAP.
- The proxy runs as a **core service** on `traefik-public` (one per node, or one
  on the control host reached over the shared network). Cookie domain
  `.<BASE_DOMAIN>` so a session covers every zone's apps.
- **Allow-list** by email domain (`@corp.com`) or IdP group — e.g. only a
  `hackathon-judges` group can open the apps.
- Applied per **zone** or per **app** (see below).
- Not available under `http-only`.

## Per-zone / per-app visibility

The deploy payload and the zone console carry a `visibility`:

| `visibility` | Router entrypoint | Middleware | Reachable by |
|---|---|---|---|
| `public` | as per the cluster profile | none | anyone who can reach the node/relay |
| `corp` | same | `forward-auth` (SSO) | signed-in, allow-listed users |
| `private` | same | `ip-allowlist` or none | only over the tunnel / private network |

`ignition-control` renders the app-compose Traefik labels from
`cluster-profile × visibility`: the entrypoint (`websecure` vs `web`), the cert
resolver (`le-dns` / `le-http` / `internal` / none), and whether the
`forward-auth` middleware is attached.

## Decision guide

Start from the topology (above), then:

- **Box on the corp DMZ, corp audience** (topology A) → `public` + `sso`, bind
  `IGN_EXPOSE_ADDR` to the DMZ IP. Certs from the corp `step-ca`, or public ACME
  if the DMZ names are delegatable.
- **Corp DMZ + a direct internet link** (B) → the above for corp names, plus
  public ACME + `public` on the internet interface for external guests.
- **Internet only, no corp link** (C) → `public` (DNS-01 wildcard, public ACME).
  Add `sso` (self-hosted Keycloak, invited accounts) if the guest list is
  closed.
- **Air-gapped, laptops in the room** (D) → `internal-ca` (`step-ca` on the box)
  or `http-only`. No remote demo.
- **Corp DMZ but no inbound allowed** (E) → `relay`: the box dials out to a
  relay you run (a DMZ box, or a VPS for an external audience); TLS terminates
  on the relay; split-horizon DNS.
- **Public IP but no DNS API** → `public-http01` (per-host HTTP-01).

## Status

Ignition today implements **`public` (DNS-01 wildcard)** only. The profiles
above are the design for making exposure a per-cluster choice — see
[`CLAUDE.md`](https://github.com/johnjoeallen/ignition/blob/main/CLAUDE.md)
"Likely next tasks".
