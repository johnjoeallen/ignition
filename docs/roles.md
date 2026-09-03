# Roles

HackZone has two admin roles and a clean line between them.

```mermaid
flowchart TB
    pa["Platform admin"]
    za["Zone admin<br/>(one per zone / team lead)"]
    dev["Team developers"]

    subgraph cp["Control plane (hz + hz-control)"]
        nodes["Nodes<br/>register · drain · capacity"]
        zones["Zones<br/>create · place · move · destroy"]
        zoneview["Zone view<br/>users · repos · apps · runner · status"]
    end

    pa --> nodes
    pa --> zones
    za --> zoneview
    zoneview -->|"proxied Forgejo admin API"| forgejo["the zone's own Forgejo"]
    za -->|"Release button → auto-tag main"| zoneview
    dev -->|git push / PRs| forgejo

    pa -. "never touches" .-> forgejo
    za -. "no node or Docker access" .-> nodes
```

## Platform admin

The person (or two) running the event. Holds `HZ_ADMIN_TOKEN`. Works through
the **`hz` CLI** and the control plane's **platform view**.

| Task | How |
|---|---|
| Register a host to run zones | `hz node add <name> <docker-host> --cpus N --mem NNg [--labels …]` |
| See node capacity / allocation | `hz node list`, `hz node show <name>` |
| Stop placing new zones on a node | `hz node drain <name>` (then `undrain`) |
| Create a zone (auto-placed) | `hz zone create <slug>` — the scheduler picks the least-loaded node that fits |
| Create a zone on a specific node | `hz zone create <slug> --node <name>` |
| See every zone and its live status | `hz zone list`, `hz zone status <slug>`, or the platform view |
| Move a zone to another node | `hz zone move <slug> --node <name>` (the stack is rebuilt; data volumes don't follow) |
| Destroy a zone | `hz zone destroy <slug>` — the stack **and every app it deployed**, complete |
| See / stop any deployed app | `hz app list`, `hz app show <zone> <name>`, `hz app rm <zone> <name>` |
| Reclaim idle zones | `hz sweep` / a cron on `scripts/sweep-idle.sh` |

The platform admin **never logs into a zone's Forgejo**. Their surface is
nodes and zones.

## Zone admin

One per zone — the team lead. Gets, at provisioning time:

- a **Forgejo admin login** (`state/zones/<slug>/zone-admin.txt`) for that
  zone's Forgejo, and
- a **zone control token** (`state/zones/<slug>/zone-token`) — they sign in
  with it at `https://admin.<slug>.<event-domain>/` to get the zone view.

Through the zone view (or Forgejo directly) they manage **their zone only**:

| Task | Where |
|---|---|
| Add / remove team members | zone view "Users", or Forgejo → Site Administration → Users |
| Create repositories | zone view "Repositories", or Forgejo → New Repository |
| Ship a build | zone view "Repositories" → pick `patch`/`minor`/`major` → **Release**. hz-control tags the next `vX.Y.Z` on `main` for you (via the Forgejo API — no local `git tag`, no Releases form) and that starts the build-and-deploy workflow. A push to `main` deploys the same way. |
| Manage the zone's apps | zone view "Apps" — list, live status, remove. Deploys come from CI; every app also gets a Watchtower agent wired in automatically, so a re-pushed image redeploys on its own (~60s). |
| Restart a stuck Actions runner | zone view button (`docker compose -p zone-<slug> restart runner`) |
| See build / deploy status, the live-app URL | zone view status card |
| Manage PRs, issues, Actions, packages | Forgejo, as normal |

Every zone-view action is either a **proxied call to that zone's own Forgejo
admin API** (with the token minted at provisioning) or a `docker compose`
command **scoped to a `zone-<slug>` or `app-<slug>-<name>` project the zone owns**. A
zone admin has no node access, no Docker access, and no visibility into any
other zone.

### Shipping a release

Releases are cut **from the zone console**, not with a local `git tag` and not
through Forgejo's Releases form — so the tag is always made by hz-control
against reviewed, already-pushed `main`.

1. Get the change onto `main` (merge the PR).
2. In the zone view under **Repositories**, each repo shows its current version
   (e.g. `· v1.2.3`). Pick the bump — `patch` for a fix, `minor` for a feature,
   `major` for a breaking change — and click **Release**.
3. hz-control reads the repo's highest `vMAJOR.MINOR.PATCH` tag, computes the
   next one, and creates it on `main` via the Forgejo API (the first release is
   `v0.1.0`, or `v1.0.0` for a major). It shows as a normal tag/release in
   Forgejo.
4. The new tag fires the `build and deploy` workflow — watch it under the
   repo's **Actions** tab; on success the app is live at
   `https://<APP_NAME>.apps.<slug>.<event-domain>/` within a minute or two.

A plain push to `main` deploys the same way (useful mid-hack); a release is the
one to use for anything a judge or stakeholder will look at, because the
running image is labelled with the version and it is trivial to redeploy or
roll back to that exact tag.

Re-pushing an image to the **same** tag later (a base-image rebuild, say) needs
no new release: the per-node Watchtower notices the new digest and rolls the
app forward on its own within ~60s.

## The line between them

- Platform admin: *which* hosts exist, *where* zones run, *whether* a zone
  exists at all.
- Zone admin: *what happens inside* one zone — people, repos, the runner, and
  shipping releases (the **Release** button auto-tags `main`).

The control plane (`hz-control`) is the single process that holds both sets of
credentials and enforces the split: it authenticates the caller's token,
decides platform-vs-zone, and only ever acts within that scope.
