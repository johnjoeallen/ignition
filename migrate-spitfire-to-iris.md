# Migrating Ignition from `spitfire` to `iris`

Move a running Ignition install from the box currently hosting it (`spitfire`)
to a new box (`iris`), **keeping every team (zone) and every Forgejo repo**.

`spitfire` today runs the whole stack — the edge Traefik, `ignition-control` +
its PostgreSQL, and (as the single node, registered with endpoint `local`) every
zone's Forgejo, DinD build engine, runner, and deployed apps. If you followed
[demo part 2](demo-2-remote-access.md) it also holds one end of a WireGuard
tunnel to `hetzner`, which SNI-routes `*.ignition.classesarecode.net` to it.

The clean way to move all of that is a **whole-box lift-and-shift**: `iris`
takes over `spitfire`'s identity wholesale. Because every per-zone Docker
resource is name-prefixed `zone-<slug>` and the node endpoint is just `local`,
nothing in the control plane's state needs to know the hardware changed — carry
the Postgres database, the two secret files, and the per-zone data volumes, and
`iris` *is* the install.

> **Recommended: [Plan A — whole-box move](#plan-a--whole-box-move).** It needs a
> maintenance window (30–60 min) but keeps repos, runners, CI history, issued
> certs, and the WireGuard config intact.
>
> [Plan B — incremental, zone by zone](#plan-b--incremental-zone-by-zone) avoids
> a full outage but is fragile and re-provisions each zone. Only if you genuinely
> can't take a window.

---

## What has to travel

| item | where it lives on `spitfire` | why it must come | skippable? |
|---|---|---|---|
| Control-plane DB | Docker volume `ignition-pgdata` (PostgreSQL) | **source of truth** — nodes, zones, apps, secrets, identity | no |
| `IGN_SECRET_KEY` | `.env` | AES key for the per-zone credentials stored in Postgres — **without it every zone secret is unreadable** | no |
| `IGN_USER_SECRET_PEPPER` | `.env` | ingredient for each git user's derived secret | no |
| rest of `.env` | `.env` | `BASE_DOMAIN`, `IGN_PUBLIC_URL`, `POSTGRES_PASSWORD`, SMTP block | no |
| ACME creds | `acme.env` | DNS-01 provider token for cert renewal | no |
| Issued certificates | Docker volume `acme` | avoids a re-issuance storm (per-zone wildcards, rate limits) | yes, but do it |
| Per-zone Forgejo data | volume `zone-<slug>-forgejo-data` per zone | **the repos** — code, PRs, issues, Actions history, registry images, users, tokens | **no — this is the point** |
| Per-zone runner data | volume `zone-<slug>-runner-data` per zone | the registered runner `config.yml`; keeping it avoids re-registration | yes (re-provision regenerates it) |
| Per-zone build cache | volume `zone-<slug>-dind-data` per zone | DinD image/layer cache — CI rebuilds without it | yes (large; CI just re-pulls) |
| WireGuard config | `/etc/wireguard/wg0.conf` | the tunnel `hetzner` routes through | no (if using demo part 2) |

**Does *not* need to travel:** `state/` (the on-disk tree under `state/` and the
`ignition-work` / `ignition-dynamic` volumes are re-rendered from Postgres on
every control-plane start), and any app container (redeploy from the console —
apps are stateless; their own DBs live in their own compose).

> If your install predates the file-tree→Postgres move and still keeps live
> state under `state/zones/…`, `rsync` that directory across too, to the same
> path in the repo clone on `iris`.

---

## Before you start

- `iris`: Docker Engine + Compose v2 installed, and enough CPU/MEM for every
  zone's quota sum (check **Nodes** in the console for the current allocation).
- Root/SSH between `spitfire` and `iris` for the volume copy.
- The list of zone slugs: **Roster** in the console, or
  `docker volume ls --format '{{.Name}}' | grep -- -forgejo-data` on `spitfire`.
- A recent `pg_dump` you've tested restoring (take one now regardless).
- Decide `iris`'s LAN IP — clients that reach Ignition by `/etc/hosts` override
  (demo part 1, and any per-zone entries — see
  [the note below](#dns-and-etchosts)) must be repointed to it.

---

## Plan A — whole-box move

### 1. Prep `iris`

```sh
git clone https://github.com/johnjoeallen/ignition.git
cd ignition
docker network create traefik-public
docker volume  create ignition-dynamic
mkdir -p ssh-empty
```

Do **not** bring anything up yet.

### 2. Quiesce `spitfire`

Stop writers first, then everything, so the DB and the Forgejo volumes are
copied cold and consistent:

```sh
cd ignition   # the repo root on spitfire

# control plane first — no more DB writes
docker compose --project-directory . -f templates/ignition-control-compose.yml stop

# then every zone stack + the edge
for v in $(docker volume ls --format '{{.Name}}' | grep -- -forgejo-data); do
  slug=${v#zone-}; slug=${slug%-forgejo-data}
  docker compose -p "zone-$slug" stop
done
docker compose --project-directory . -f templates/traefik-core-compose.yml stop
```

Leave the containers *stopped, not removed* until `iris` is proven — this is the
rollback.

### 3. Carry the secret files

```sh
scp spitfire:~/ignition/.env      iris:~/ignition/.env
scp spitfire:~/ignition/acme.env  iris:~/ignition/acme.env
ssh iris 'cd ignition && chmod 600 .env acme.env'
```

### 4. Move the control-plane database

Bring up **only Postgres** on `iris`, then load a dump:

```sh
# iris
docker compose --project-directory . -f templates/ignition-control-compose.yml up -d postgres

# spitfire → iris, streamed
ssh spitfire 'docker exec -i ignition-postgres pg_dump -U ignition -Fc ignition' \
  | ssh iris 'docker exec -i ignition-postgres pg_restore -U ignition -d ignition --clean --if-exists'
```

(If `spitfire`'s Postgres is stopped, `docker start ignition-postgres` on it for
the dump, then stop it again.)

### 5. Copy the data volumes

A helper that streams one volume `spitfire` → `iris` with no intermediate file:

```sh
copy_vol() {   # run from iris
  local v=$1
  docker volume create "$v" >/dev/null
  ssh spitfire "docker run --rm -v $v:/from alpine tar -C /from -cf - ." \
    | docker run --rm -i -v "$v:/to" alpine tar -C /to -xf -
}

copy_vol acme

for slug in quantum-badgers burning-jaguars <...every slug...>; do
  copy_vol "zone-$slug-forgejo-data"
  copy_vol "zone-$slug-runner-data"    # keeps the runner registered
  copy_vol "zone-$slug-dind-data"      # optional — omit to save time/space
done
```

Generate the loop body from `spitfire` if you'd rather not type slugs:

```sh
ssh spitfire "docker volume ls --format '{{.Name}}' \
  | grep -E -- '-(forgejo|runner|dind)-data$'"
```

### 6. Repoint the WireGuard tunnel

Give `iris` `spitfire`'s exact WireGuard config — same private key, same
`10.44.0.11` address — so **`hetzner` needs no change** (it still has one peer at
that key and IP):

```sh
ssh spitfire 'systemctl disable --now wg-quick@wg0'
scp spitfire:/etc/wireguard/wg0.conf iris:/etc/wireguard/wg0.conf
ssh iris 'systemctl enable --now wg-quick@wg0'
ssh hetzner 'wg show'   # expect a handshake with iris within ~25s (PersistentKeepalive)
```

If you'd rather `iris` have its own key: generate a keypair on `iris`, put its
public key + `AllowedIPs = 10.44.0.11/32` in `hetzner:/etc/wireguard/wg0.conf`
(replacing the old peer), `systemctl restart wg-quick@wg0` on `hetzner`.

Skip this whole step if you never did demo part 2 (LAN-only install).

### 7. Bring `iris` up

```sh
cd ignition   # iris

docker compose --project-directory . -f templates/traefik-core-compose.yml up -d

IGN_RECREATE_ZONES_ON_START=true \
  docker compose --project-directory . -f templates/ignition-control-compose.yml up -d
```

`IGN_RECREATE_ZONES_ON_START=true` makes the control plane, on this one startup,
re-render every zone's compose from Postgres and `docker compose up -d` it
against the volumes you copied (it never passes `-v`, so no data is touched).
Drop the flag on subsequent restarts.

Watch it settle:

```sh
docker compose --project-directory . -f templates/ignition-control-compose.yml logs -f ignition-control
docker compose --project-directory . -f templates/traefik-core-compose.yml logs -f traefik | grep -i acme
```

Certs come from the copied `acme` volume; Traefik only calls the CA for anything
missing or near expiry.

### 8. Redeploy the apps

App containers are stateless and aren't in the volume copy. For each live app,
open its team console → **Repositories** and hit **Deploy** again (or **Release**
if you want a fresh build). The image is still in that zone's Forgejo registry,
so a redeploy is a pull + run.

### 9. Verify

- Console health: `curl -I https://<BASE_DOMAIN>/actuator/health` → `200`.
- Sign in; **Roster** shows every zone; **Nodes** shows the node still `ACTIVE`.
- `git clone https://git.<slug>.<BASE_DOMAIN>/<org>/<repo>.git` for one zone —
  history intact, PRs and issues present in the web UI.
- One app URL: `https://<app>.apps.<slug>.<BASE_DOMAIN>/`.
- Cut a test release in a throwaway repo — build runs on the (copied) runner,
  pushes to the registry, `/deploy` rolls forward.

### 10. The node record still says `spitfire`

The node's endpoint is `local`, so it keeps working on `iris` unchanged — the
name is cosmetic. A node's name is its immutable id and can't be edited, and it
can't be removed while zones are assigned, so either live with the label or,
during a later window, `prepareMove` every zone to a freshly-registered `iris`
node (this re-provisions — see Plan B's caveats about Forgejo data).

### 11. Decommission `spitfire`

Once `iris` has served real traffic for a day or two:

```sh
# spitfire
cd ignition
docker compose --project-directory . -f templates/ignition-control-compose.yml down
docker compose --project-directory . -f templates/traefik-core-compose.yml down
# keep the volumes a while longer as a cold backup, then:
# docker volume ls --format '{{.Name}}' | grep -E '^(zone-|ignition-|acme)' | xargs docker volume rm
rm /etc/wireguard/wg0.conf
```

---

## DNS and `/etc/hosts`

- **Reaching Ignition through `hetzner` (demo part 2):** clients use real public
  DNS (`*.ignition.classesarecode.net` → `hetzner`), and step 6 keeps the tunnel
  address the same. **Nothing to change on clients.**
- **LAN-only install (demo part 1), or any client with a manual override:** every
  `/etc/hosts` entry pointing at `spitfire`'s LAN IP must be repointed to
  `iris`'s. This includes the apex entry from part 1 **and** any per-zone entries
  (`git.<slug>.ignition.classesarecode.net`,
  `<app>.apps.<slug>.ignition.classesarecode.net`) that were added by hand on
  client machines — a new zone's hostnames don't resolve on a client until such
  an entry exists, and moving boxes invalidates all of them at once. Sweep every
  client's `/etc/hosts` as part of the cutover.

---

## Plan B — incremental, zone by zone

Use this only if a maintenance window is impossible. It's more moving parts and
each zone is **re-provisioned** (fresh runner registration, fresh `ignition-bot`
token), so it's inherently riskier than Plan A.

The control plane stays on `spitfire` for the transition; `iris` joins as a
second node.

1. **Register `iris` as a node.** On `iris`: install Docker, bring up
   `traefik-core-compose.yml` only, `docker volume create ignition-dynamic`.
   Expose its daemon to the control plane over SSH (`ssh://root@iris`) with a key
   in `spitfire:~/ignition/ssh-empty` (rename to a real dir and set `IGN_SSH_DIR`),
   or `tcp://`+TLS. In the console: **Nodes → Register**, endpoint
   `ssh://root@iris`, real specs.
2. **Drain `spitfire`.** **Nodes →** set `spitfire` to `DRAINING` so nothing new
   lands there.
3. **Per zone**, one at a time:
   a. Stop the zone on `spitfire`: `docker compose -p zone-<slug> stop`.
   b. Copy `zone-<slug>-forgejo-data` (and `-runner-data`, `-dind-data`) to
      `iris` with the `copy_vol` helper from Plan A step 5.
   c. **Teams → `<slug>` → Move** to `iris`. This tears the zone down
      keeping its DB row, drops the per-node credentials, and re-provisions on
      `iris` — which brings Forgejo up on the volume you just placed, so the
      repos are already there. Watch the provisioning log; if Forgejo comes up
      empty, the volume copy landed after provisioning recreated it — stop the
      zone, re-copy, `docker compose -p zone-<slug> up -d`.
   d. Verify that zone (clone a repo, check a PR), then redeploy its apps.
4. When every zone is on `iris`, **move the control plane**: Plan A steps 2–7 for
   just the DB + `.env`/`acme.env` + `acme` volume + WireGuard, with the node
   endpoints already pointing at `iris`. Then **Nodes → remove `spitfire`**.

Plan B's re-provision means step 3c is the fragile point — the Forgejo data
volume "follows" only because you pre-place it and provisioning happens to mount
an existing volume rather than a fresh one. Test it on a throwaway zone first.

---

## Rollback

Nothing on `spitfire` is deleted until step 11 / Plan B step 4. To roll back
before then: stop `iris`, `systemctl enable --now wg-quick@wg0` on `spitfire`
(and revert `hetzner`'s peer if you regenerated keys), restart the stopped
`spitfire` stack (`docker compose … start`). The only lost work is anything
committed to a zone on `iris` after cutover — which is why the window matters.
