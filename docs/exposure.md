# Exposure & access

!!! note "Status — one proposed model, draft"
    This is **one** exposure/access design among several the project is weighing,
    written up in full because it's the current front-runner — not a locked
    decision. It is **not** how the prototype runs today; for that see
    [End to End](end-to-end.md), which uses a simpler two-machine demo topology.
    Treat this page as a proposal.

A single model, deliberately: no per-topology choices, no hosted services.

```
            clients / internet
                    │  :443 (and :80 for ACME / redirect)
        ┌───────────▼────────────┐
        │      controller        │   public internet, corp subnet, or both
        │  ── Traefik edge ──    │   • terminates TLS (the only place certs live)
        │  ── SSO gateway ──     │   • one forward-auth to your IdP for browser traffic
        │  ── ignition-control ──│   • routes by Host → the node running the zone
        └───────────┬────────────┘
                    │  WireGuard  (plain HTTP inward — no TLS behind the edge)
     ┌──────────────┼──────────────┐
     ▼              ▼              ▼
 ┌────────┐    ┌────────┐    ┌────────┐   private network, NO inbound
 │ node-1 │    │ node-2 │    │ node-3 │   • internal-only Traefik on :80
 │ zones… │    │ zones… │    │ zones… │   • Forgejo / DinD / runner / apps
 └────────┘    └────────┘    └────────┘
```

- The **controller** is the only machine that accepts inbound traffic and the
  only thing that terminates TLS. It runs the Traefik **edge**, the **SSO
  gateway**, and `ignition-control`. Where it sits is a deployment choice: on
  the public internet with a public IP, on a private subnet routable only from
  the corporate network, or dual-homed on both. Nothing in the design assumes a
  public address — only that clients (browsers, `git`/`docker`) and the nodes
  can each reach it.
- **Nodes** sit on a private network with **no inbound at all**. The controller
  reaches them over a **WireGuard** link (`ignition-control` adds each node as a
  peer at registration). Everything behind the edge is **plain HTTP** — the
  private link is the confidentiality boundary.
- Every team's `traefik-public` / `zone-<slug>` / DinD network is Docker-local
  on its node, with no route out.

## DNS — pre-registered, once

Set up **before** any event, never touched again:

- **Delegate `<BASE_DOMAIN>` to the controller** (`<BASE_DOMAIN>  NS  <controller>`)
  and let it run a tiny authoritative DNS (CoreDNS / Technitium). It answers a
  wildcard `*.<BASE_DOMAIN>  →  <controller-IP>` at any depth (RFC 4592),
  so `<BASE_DOMAIN>` itself, `git.qb.<BASE_DOMAIN>`, and
  `x.apps.qb.<BASE_DOMAIN>` all resolve to the controller with **no per-team
  record**. Provisioning a team adds zero DNS.
- Or, if you'd rather not delegate: pre-register `*.<BASE_DOMAIN>  A  <controller>`
  in your existing DNS and hand the controller a DNS-provider API token so it
  can still do the ACME challenge.

Corporate machines resolve it through their normal resolver — it's just a
domain (the name can be public even when the address it points to is only
reachable from the corporate network). If corp egress goes through an explicit HTTP proxy,
`*.<BASE_DOMAIN>` has to be on its allow list.

## TLS — at the edge only

The edge obtains certs from an ACME CA (Let's Encrypt, or your own `step-ca` via
`ACME_CA_SERVER`) using **DNS-01**:

- one `*.<BASE_DOMAIN>` cert (covers `<BASE_DOMAIN>` and every single-label
  name), plus
- per team, `*.<slug>.<BASE_DOMAIN>` + `*.apps.<slug>.<BASE_DOMAIN>` (cert
  wildcards are single-label, so these are two labels deep). `ignition-control`
  requests them as it provisions, alongside the router config.

DNS-01 means the CA never connects to the controller, so the SSO gateway can
sit in front of literally everything with **nothing to bypass**. Behind the
edge there is no TLS — node Traefik and the app containers speak plain HTTP over
WireGuard.

If you truly cannot get a trusted cert (an offline demo, laptops in a room),
run the edge on `:80` only; `ignition-control` marks every URL `http://` and
refuses to enable SSO (an unauthenticated redirect over plain HTTP leaks the
session).

## SSO — one gateway, no software on the dev machine

Every **browser** request — the platform console, the team console, Forgejo's
web UI (PRs, CI logs, Actions), and the deployed apps — passes through one
`forward-auth` gateway at the edge that redirects to your IdP. A developer
needs only a browser and a corporate login; nothing is installed on their
machine. "Managed devices only" is a **Conditional Access policy at the IdP**,
not an Ignition feature.

- **Traefik `forwardAuth` → `oauth2-proxy`** (or **Authelia**), against your
  OIDC provider — self-hosted **Keycloak**, or an existing corp Entra ID / Okta
  / LDAP. Cookie domain `.<BASE_DOMAIN>`, so one session covers everything.
- **Allow-list** by email domain or IdP group (e.g. a `hackathon-judges`
  group). Contractors get an IdP guest account, not a carve-out.

**`git push` / `docker push` can't do OIDC.** The gateway **bypasses** requests
carrying HTTP Basic auth or a git/registry user-agent and lets Forgejo
authenticate them with a **personal access token** the developer mints in the
Forgejo UI after their first SSO'd login — exactly like a GitHub PAT, over
HTTPS. Forgejo SSH stays disabled.

## Per-app visibility

The deploy payload and the team console carry a `visibility`:

| `visibility` | Behind the SSO gateway? | Reachable by |
|---|---|---|
| `corp` (default) | yes | signed-in, allow-listed users |
| `public` | no | anyone who can reach the controller |
| `private` | yes + an IP allow-list | a named CIDR only |

`ignition-control` renders the app router config accordingly — it's just
whether the `forward-auth` (and `ip-allowlist`) middleware is attached; the
entrypoint and cert are always the edge's.

## Where this sits

This is the architecture — see [Architecture → Ingress](architecture.md#ingress-one-front-door).
The per-node Traefik stays, internal-only, for the final `Host` → container hop;
everything public terminates at the controller's edge.
