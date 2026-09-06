# Bringing Ignition up on `iris` — as-built record

A fresh Ignition platform stood up on **`iris`** on **2026-09-05**, replacing
`spitfire`. Not a data migration: new empty Postgres, new zones. Only two things
carried over — the **WireGuard config** (so `hetzner`'s edge routing is
unchanged) and **one repo** (`galatica`), re-pushed by hand afterwards.

Deployment was clean apart from the first zone's Forgejo provisioning, which
took three attempts (see [§5](#5-first-zone-provisioning-3-attempts)).

`iris` is not a plain server — it's a router appliance (`lan0`, `wan0`,
`wwan0` cellular uplink, Tailscale, Docker 29) with an **nftables `forward`
chain whose policy is `drop`** and a hand-maintained allow-list in
`/etc/nftables.d/`. Four of the fixes below are consequences of that.

---

## 0. Preconditions (done before this record starts)

- `hetzner`'s nginx SNI proxy on `<PUBLIC_IP>:443` forwards
  `*.ignition.classesarecode.net` down the WireGuard tunnel to `10.44.0.11:443`
  (demo part 2). *(It was found stopped at one point during this work — if the
  site won't load, check `systemctl status nginx` on `hetzner` first.)*
- **WireGuard moved to `iris`:** `iris` was given `spitfire`'s exact
  `/etc/wireguard/wg0.conf` — same private key, same `10.44.0.11/24` — so
  `hetzner`'s peer config needed no change. `wg-quick@wg0` disabled on
  `spitfire`, enabled on `iris`. Verified with `wg show` (handshake with
  `135.181.6.172:51820`).
- `spitfire`'s old stack was left running but orphaned (it no longer has a
  `wg0`, so nothing routes to it).

---

## 1. Repo + environment on `iris`

```sh
cd ~/git && git clone https://github.com/johnjoeallen/ignition.git
cd ~/git/ignition
```

`.env` and `acme.env` copied verbatim from `spitfire:~/git/ignition/` — same
`BASE_DOMAIN=ignition.classesarecode.net`, same SMTP block, same Joker DNS-01
credentials. `chmod 600` both. (Secrets like `IGN_SECRET_KEY` were reused too;
harmless here since there was no old data to decrypt, and it avoids a config
divergence.)

---

## 2. Free port 80

`iris` was running stock Debian Apache on `:80` (default vhost, nothing real),
which blocks Traefik:

```sh
sudo systemctl disable --now apache2
```

---

## 3. Firewall — allow container egress  *(iris-specific)*

Symptom: `ignition-control` and Postgres talked to each other fine, but any
container reaching the **internet** timed out (ACME `dial tcp … i/o timeout`,
DNS failures) while the host itself had connectivity. Docker image pulls worked
because those run from the daemon (host), not a container.

Cause: `/etc/nftables.d/20-forward-rules.nft` allowed `lan0`/`wwan0` → Docker
bridges but had **no rule for Docker bridges → `wan0`/`wwan0`**, and the
`forward` chain policy is `drop`.

Fix (appended to that file, and applied live):

```nft
add rule inet filter forward iifname "docker0" oifname { "wan0", "wwan0" } accept
add rule inet filter forward iifname "br-*"    oifname { "wan0", "wwan0" } accept
```

---

## 4. Bring the stack up

```sh
cd ~/git/ignition
./update-and-run.sh          # pulls images, down/up traefik-core + ignition-control
```

Two problems surfaced here:

### 4a. Traefik ↔ Docker 29 API mismatch  *(iris-specific)*

Traefik `v3.5` (the compose default) against `iris`'s Docker 29
(server API 1.55, **min 1.40**) failed every few hundred ms with
`client version 1.24 is too old`, even with `DOCKER_API_VERSION=1.44` set on the
container. Fixed by pinning a newer Traefik in `.env`:

```sh
echo 'TRAEFIK_VERSION=v3.6' >> .env
docker compose --project-directory . -f templates/traefik-core-compose.yml up -d
```

(Running `traefik:v3.6.25` now — the docker provider and file provider both load
clean.)

### 4b. ACME then succeeded

Once §3 was in place, Traefik obtained the real wildcard cert via DNS-01
(Joker): `CN=…, O=Let's Encrypt` for `ignition.classesarecode.net` +
`*.ignition.classesarecode.net`. `curl -I https://ignition.classesarecode.net/`
locally on `iris` → `200`.

---

## 5. First zone provisioning — 3 attempts

Registered the node in the console (**Nodes → Register**: name `iris`, endpoint
`local`, 12 CPU / 58 GB), then provisioned team `temporal-dragons`. It failed
twice before succeeding; each failure left the zone half-created (zone row, no
`forgejo_token` secret) so **every Forgejo call returned `503 — zone has no
Forgejo admin token yet`**. There is no "retry provisioning" action — each
recovery was **destroy the zone, then provision again**.

| # | failure | cause | fix |
|---|---|---|---|
| 1 | `compose up forgejo+dind failed: dind Pulling` | zone images not cached; the pull over the cellular uplink outlasted the provisioning step's wait | pre-pull on `iris` (below) |
| 2 | `compose up forgejo+dind failed: Network zone-… Creating` → `iptables: No chain/target/match by that name` | **self-inflicted**: persisting the §3 rule with `systemctl reload nftables` runs `flush ruleset`, which wiped Docker's `DOCKER-FORWARD` / `DOCKER-*` chains. Running containers survived; any *new* `docker network create` broke. | `sudo systemctl restart docker` (rebuilds Docker's chains on top of the current base ruleset) |
| 3 | — (succeeded) | images cached, networking intact | — |

Pre-pull done before attempt 2:

```sh
docker pull codeberg.org/forgejo/forgejo:11
docker pull docker:27-dind
docker pull code.forgejo.org/forgejo/runner:13
```

**Rule for `iris` going forward:** never `systemctl reload nftables` without
following it with `sudo systemctl restart docker`. Prefer applying single rules
live with `nft add rule` (surgical, doesn't flush) and only appending to the
`.nft` file for persistence.

On attempt 3 the control-plane log showed the full happy path — admin PAT
minted, org/team/member created, runner registered, runner container stable
(`Up`, no longer crash-looping).

---

## 6. Firewall — allow the inbound tunnel  *(iris-specific)*

Symptom: console loaded fine from a **LAN** client but not through `hetzner`.
`tcpdump -ni wg0` showed inbound SYNs from `10.44.0.1` to `10.44.0.11:443` with
**no SYN-ACK** — dropped.

Cause: same `forward` chain (policy `drop`) had rules for `lan0` and `wwan0`
into the Docker bridges but **none for `wg0`**. LAN access worked only because
`iifname "lan0" oifname "br-*" accept` already existed.

Fix (appended to `/etc/nftables.d/20-forward-rules.nft`, applied live with
`nft add rule` — no reload, so Docker's chains were left intact):

```nft
add rule inet filter forward iifname "wg0" oifname { "docker0", "br-*" } accept
```

---

## 7. Final state — verified

| check | result |
|---|---|
| `docker compose ps` (both stacks) | traefik `v3.6.25`, watchtower, `ignition-control` (healthy), postgres (healthy) |
| `https://ignition.classesarecode.net/` from outside | `302 → /login`, valid Let's Encrypt cert |
| Node `iris` | registered, `local`, `ACTIVE` |
| Zone `temporal-dragons` | forgejo (healthy) + dind + runner (stable), org/team/member set |
| `https://git.temporal-dragons.ignition.classesarecode.net/` from outside | `200`, valid Let's Encrypt cert |
| repo `temporal-dragons/galatica` | present (control plane reads its branches/tags/PRs); `galatica` re-pushed by hand |

---

## 8. Net changes on `iris` (for teardown / rebuild)

- `apache2` disabled.
- `~/git/ignition` clone; `.env` has an added `TRAEFIK_VERSION=v3.6` line.
- `/etc/nftables.d/20-forward-rules.nft` — three appended rules (§3 ×2, §6 ×1).
- `wg0` (moved from `spitfire`).
- Docker: the two Ignition stacks, the `temporal-dragons` zone stack, cached
  Forgejo/DinD/runner images.

## 9. Follow-ups worth doing

- **Upstream the Traefik default** — `traefik-core-compose.yml` should default to
  `v3.6`+ (or document that Docker ≥ 27 needs it).
- **Provisioning robustness** — attempts 1 & 2 both failed on a slow/blocked
  `compose up` and left an unrecoverable half-zone with no retry path. A longer
  first-run image wait, or a "reprovision" action that re-runs phase 2 on an
  existing zone, would have turned three destroy/recreate cycles into one.
- **`iris` nftables + Docker** — the reload-wipes-Docker-chains trap should be
  handled in the router's config (e.g. Docker's rules in a table the base
  config doesn't flush, or a `systemctl restart docker` in an nftables reload
  hook).
- **Decommission `spitfire`** once `iris` has run for a few days
  (`docker compose … down` both stacks; keep volumes as a cold backup first).
