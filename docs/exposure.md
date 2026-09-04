# Exposure & access

The default — public inbound on the node, wildcard certs from Let's Encrypt via
the DNS-01 challenge — is one option among several. A cluster picks an
**exposure profile** at install, and individual zones or apps can narrow it.

Three independent knobs:

1. **Reachability** — how a request gets to the node: direct **inbound**, or an
   outbound **reverse tunnel** (no inbound ports).
2. **TLS** — a publicly-trusted cert, a corporate internal-CA cert, or (last
   resort) plain HTTP.
3. **Authorization** — open to anyone who can reach it, or gated by **corporate
   SSO**, or reachable only over a private network (the tunnel *is* the boundary).

| Profile | Reachability | TLS | Typical audience |
|---|---|---|---|
| `public` (default) | inbound `:80/:443` on the node | Let's Encrypt (DNS-01 wildcard) | the internet |
| `public-http01` | inbound `:80/:443`, no DNS API | Let's Encrypt (HTTP-01, per host) | the internet |
| `cloudflare-tunnel` | outbound only | Cloudflare edge cert | the internet, or corp via WARP |
| `tailscale` | outbound only (WireGuard) | `*.ts.net` cert (Funnel) or tailnet-only | tailnet members, or public via Funnel |
| `frp` / `inlets` | outbound to a relay you run | whatever the relay terminates | wherever the relay lives |
| `corp-ca` | inbound, reachable only from the corp network | your internal ACME / CA | corp devices |
| `http-only` | inbound | none | closed demo network only |

SSO (`corp-sso`) is layered on **any** of the above.

---

## 1. Public inbound

The node's Traefik owns `:80` / `:443`. Requests arrive directly.

- **DNS**: `git.<slug>` / `*.apps.<slug>` A-records → the node running the zone;
  `admin.<slug>` and `admin.<BASE_DOMAIN>` → the control host.
- **Certs — DNS-01 wildcard (preferred)**: Traefik answers the ACME challenge
  by writing a TXT record through the DNS provider's API, so it can obtain
  `*.<slug>.<BASE_DOMAIN>` + `*.apps.<slug>.<BASE_DOMAIN>` without any inbound
  reachability during issuance. This is what `traefik-core-compose.yml` does
  today (`CF_DNS_API_TOKEN` etc.).
- **Certs — HTTP-01 (`public-http01`)**: if you can't give Traefik DNS API
  access, it falls back to a per-hostname HTTP-01 challenge — Let's Encrypt
  connects to `:80` for each `<app>.apps.<slug>.<BASE_DOMAIN>` as it first
  appears. Needs `:80` open to the internet and a real A-record per app (no
  wildcard). Slower, rate-limited, and every new app pays a first-request TLS
  handshake delay.

Use this when the cluster has a routable public IP and you control the DNS zone.

## 2. Reverse tunnel — public audience

The cluster has **no inbound** (home connection, NAT, a locked-down VLAN). A
tunnel daemon on the control host dials out over `:443` and a provider routes
public traffic back through it.

**Cloudflare Tunnel** (`cloudflared`):

- One `cloudflared` container on the control host, one Tunnel, one credentials
  file. Ingress rules map `*.apps.<slug>.<BASE_DOMAIN>` (and the `admin.*` /
  `git.*` hosts) to `http://traefik:80` on `traefik-public`.
- **DNS**: a `CNAME` per wildcard (or per host) → `<tunnel-id>.cfargotunnel.com`.
- **TLS**: Cloudflare terminates with a publicly-trusted cert at its edge.
  Inside the cluster Traefik serves plain HTTP to `cloudflared` over the shared
  network — set the app routers to the `web` entrypoint, no cert resolver.
- Only outbound `:443` from the control host is required.

**Tailscale Funnel**:

- A `tailscale` container on the control host joins the tailnet; `tailscale
  funnel` publishes the Traefik port. Public URLs are `https://<node>.<tailnet>.ts.net/...`
  with a Tailscale-issued cert. Good for a quick share; less flexible on custom
  hostnames.

Either way `ignition-control` records the effective scheme + host so the console
and `/info` show the right URL.

## 3. Reverse tunnel — corporate audience

The audience is **inside** the corporate network; the cluster may be a cloud
box, a lab machine, or on the internet. Anchor the tunnel on the corp side
instead of a public relay.

- **Cloudflare Tunnel + private network / WARP**: the Tunnel exposes the
  cluster as a private network route; only devices enrolled in WARP (i.e. corp
  devices) can reach it. No public DNS, no public exposure.
- **Tailscale subnet router / `tailscale serve`**: the cluster joins the corp
  tailnet; apps are reachable at `<host>.<tailnet>.ts.net` **only** to tailnet
  members. Or run a subnet router so `*.apps.<slug>.<BASE_DOMAIN>` (a private
  range) routes over the tailnet.
- **`frp` / `inlets` to a DMZ relay**: the org runs the relay inside its
  perimeter; the cluster dials into it; corp DNS points `*.apps.<slug>` at the
  relay.
- **Split-horizon DNS**: corp resolvers answer `*.apps.<slug>.<BASE_DOMAIN>`
  with the relay / tailnet address; the same names don't resolve (or resolve to
  nothing) outside.
- **TLS**: use the org's **internal ACME** (`step-ca` / smallstep, or ADCS with
  an ACME shim) — point Traefik's cert resolver at it and managed devices
  already trust the chain. If there's no internal ACME, the tunnel provider's
  cert (Tailscale `*.ts.net`, Cloudflare edge) still works for corp users.

## 4. Plain HTTP (`http-only`)

When there is genuinely no way to get a trusted cert and the audience is on a
closed network you control:

- Traefik serves the `web` entrypoint only; no HTTPS redirect, no cert
  resolver.
- `ignition-control` marks every app URL `http://…` in the console and on the
  `/info` page, and refuses to enable SSO (an unauthenticated redirect over
  plain HTTP leaks the session).
- Never use this for anything carrying credentials or judging feedback.

## TLS options, summarised

| Option | Needs | Gives |
|---|---|---|
| **DNS-01 wildcard** | DNS-provider API token | one `*.<slug>` + `*.apps.<slug>` cert per zone, issued with zero inbound |
| **HTTP-01 per host** | `:80` reachable from the internet, an A-record per app | a cert per app, on first request |
| **Tunnel-terminated** | a tunnel (Cloudflare / Tailscale) | a trusted cert at the provider edge; cluster speaks plain HTTP internally |
| **Corp internal ACME** | `step-ca` / smallstep (or ADCS+ACME) reachable from the node | trusted-on-managed-devices certs, any hostname, no internet |
| **none** | — | plain HTTP only |

## Corporate SSO

Independent of reachability. An identity proxy sits in front of the app
routers; unauthenticated requests bounce to the corp IdP.

- **Traefik `forwardAuth` middleware → `oauth2-proxy`** (or **Authelia** /
  **Pomerium**), configured with the org's **OIDC** provider (Entra ID, Okta,
  Google Workspace, Keycloak).
- The proxy runs as a **core service** on `traefik-public` (one per node, or one
  on the control host reached over the shared network). Its cookie domain is
  `.<BASE_DOMAIN>` so a session covers every zone's apps.
- **Allow-list** by email domain (`@corp.com`) or IdP group — e.g. only the
  `hackathon-judges` group can open the apps.
- Applied per **zone** or per **app** (see below): the router gets the
  `forward-auth@file` middleware, or not.
- Not available under `http-only`.

A common shape: `cloudflare-tunnel` **and** `corp-sso` — no inbound, a trusted
cert, and only signed-in corp users can see anything. Cloudflare Access can
also do the SSO layer itself (skip `oauth2-proxy`) if you're already on
Cloudflare.

## Per-zone / per-app visibility

The deploy payload and the zone console carry a `visibility`:

| `visibility` | Router entrypoint | Middleware | Reachable by |
|---|---|---|---|
| `public` | as per the cluster profile | none | anyone who can reach the node/tunnel |
| `corp` | same | `forward-auth` (SSO) | signed-in, allow-listed corp users |
| `private` | same | `ip-allowlist` or none | only over the tunnel / private network |

`ignition-control` renders the app-compose Traefik labels from
`cluster-profile × visibility`: the entrypoint (`websecure` vs `web`), the cert
resolver (`le-dns` / `le-http` / `corp` / none), and whether the
`forward-auth` middleware is attached.

## Decision guide

- **Public IP + you control DNS** → `public` (DNS-01 wildcard).
- **Public IP, no DNS API** → `public-http01`.
- **No inbound, audience is the internet** → `cloudflare-tunnel` (or
  `tailscale` Funnel for a quick one-off).
- **No inbound, audience is corporate** → `cloudflare-tunnel` + WARP, or
  `tailscale` on the corp tailnet; certs from an internal ACME if you have one.
- **Everything must stay inside the perimeter** → `corp-ca` + `corp-sso`, or a
  tunnel to a DMZ relay.
- **Truly nothing works** → `http-only`, closed network, no SSO.

## Status

Ignition today implements **`public` (DNS-01 wildcard)** only. The profiles
above are the design for making exposure a per-cluster choice — see
[`CLAUDE.md`](https://github.com/johnjoeallen/ignition/blob/main/CLAUDE.md)
"Likely next tasks".
