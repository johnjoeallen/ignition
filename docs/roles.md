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
        zoneview["Zone view<br/>users · repos · runner · status"]
    end

    pa --> nodes
    pa --> zones
    za --> zoneview
    zoneview -->|"proxied Forgejo admin API"| forgejo["the zone's own Forgejo"]
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
| Destroy a zone | `hz zone destroy <slug>` — `docker compose -p zone-<slug> down -v`, complete |
| Reclaim idle zones | `hz sweep` / a cron on `scripts/sweep-idle.sh` |

The platform admin **never logs into a zone's Forgejo**. Their surface is
nodes and zones.

## Zone admin

One per zone — the team lead. Gets, at provisioning time:

- a **Forgejo admin login** (`state/zones/<slug>/zone-admin.txt`) for that
  zone's Forgejo, and
- a **zone control token** (`state/zones/<slug>/zone-token`) for the control
  plane's zone view.

Through the zone view (or Forgejo directly) they manage **their zone only**:

| Task | Where |
|---|---|
| Add / remove team members | zone view "Users", or Forgejo → Site Administration → Users |
| Create repositories | zone view "Repositories", or Forgejo → New Repository |
| Restart a stuck Actions runner | zone view button (`docker compose -p zone-<slug> restart runner`) |
| See build / deploy status, the live-app URL | zone view status card |
| Manage PRs, issues, Actions, packages | Forgejo, as normal |

Every zone-view action is either a **proxied call to that zone's own Forgejo
admin API** (with the token minted at provisioning) or a `docker compose`
command **scoped to that zone's compose project**. A zone admin has no node
access, no Docker access, and no visibility into any other zone.

## The line between them

- Platform admin: *which* hosts exist, *where* zones run, *whether* a zone
  exists at all.
- Zone admin: *what happens inside* one zone — people, repos, the runner.

The control plane (`hz-control`) is the single process that holds both sets of
credentials and enforces the split: it authenticates the caller's token,
decides platform-vs-zone, and only ever acts within that scope.
