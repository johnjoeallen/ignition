# Release / dev / PR-preview parity: plan

Status: **plan — not started.** Written against `e1e64d5` (v0.117.25). It builds
on [ENVIRONMENTS-DESIGN.md](ENVIRONMENTS-DESIGN.md) (which is about *promotion
gates*); this one is about making the three kinds of deployment **equally
visible, equally controllable and equally observable**.

## Goal

An app can be running in three ways:

| kind | trigger | host | what it runs |
|---|---|---|---|
| **release** | a version tag (console Release buttons) | `<app>.apps.<slug>…` | that tag |
| **dev** | **Deploy from main** button | `<app>.dev.<slug>…` | latest `main` |
| **PR preview** | `deploy-preview` label on a PR | `<repo>-pr-<n>.apps.<slug>…` | the PR head |

Today they share a build pipeline but differ in nearly everything a person
*does* with them afterwards. The goal: **a team member looking at any of the
three sees the same things and can do the same things**, and the platform
treats them with the same rules. Only the trigger, the URL and the default
lifetime differ — and those are data, not code paths.

## Where we are (checked in the code)

| capability | release | dev | PR preview |
|---|---|---|---|
| Row in the team page | Apps table | Dev table | **none** (Apps table is built from repos) |
| Status icon (live/dead, polled) | yes | yes | **no** |
| Stop / Start / Delete in the console | yes | yes | **no** — only a platform admin's `undeploy`, or CI/the reaper |
| Stored in | `app` | `app_dev` | `app`, as a row named `<repo>-pr-<n>` |
| Idle TTL setting | per app (`app_config`) | shares the app's | **cannot be set** — config is keyed by name, previews have none; default applies |
| Idle sweep stops it | yes | yes | yes (treated as a release) |
| Wake-on-request | yes | yes | yes (via the `app` row) |
| Secrets | `release` set | `dev` set | `preview` set |
| Restart-with-new-secrets button | yes | yes | n/a (next push) |
| Where you find it | Apps table, repo page | Dev table, repo page | a bare link list on the repo page |
| Lifetime | until deleted/stopped | until deleted/stopped | PR close, or the hourly `PreviewReaper` |
| Deploy history | none (row is overwritten) | none | none |
| Who may trigger it | **any team member** | **any team member** | whoever can label a PR |

Underlying cause: three representations (`app`, `app_dev`, previews hiding in
`app`), and every feature (stop, start, status, idle, wake, secrets, UI) was
built per representation, so each new feature lands on two of the three.

## Principles

1. **One concept:** a *deployment* = (zone, app, kind, instance). `instance` is
   empty for release and dev, the PR number for a preview.
2. **One lifecycle service:** deploy, redeploy, stop, start, delete, wake, idle
   stop, status and secrets all take a deployment, never a channel flag.
3. **Differences are configuration:** default idle time, retention after PR
   close, quota, visibility — a small per-kind policy table, overridable per app.
4. **Nothing user-visible disappears.** Hosts, compose project names
   (`app-<slug>-<name>[-dev]`) and the `/deploy` API stay as they are.

## Plan

### Phase 0 — close the visible gaps first (small, no schema change)

Cheap, independently useful, and makes the later phases easier to verify.

- **Show PR previews in the team page**, as rows under their app (or a
  third table), with the same status icon, Open link, and **Stop / Start /
  Delete** the Dev table has. Reuse `liveStates` (it already covers every
  `app` row) and the existing `apps/stop|start` endpoints.
- **Let previews inherit their repo's idle time.** In the sweeper and wake path,
  resolve `<repo>-pr-<n>` to `<repo>` for `app_config`. Previews then follow the
  app's setting instead of silently using the global default.
- **Add the missing role check** to `release()`, deploy-from-main and (new)
  preview actions. Decide the minimum: team admin, or a "can deploy" bit —
  see Decisions. This is the one security-relevant item here.

Size: **S–M.** Deliverable: all three kinds are listed, monitored and
controllable; the earlier table's first rows are all "yes".

### Phase 1 — one `deployment` table

Replace `app` + `app_dev` (+ previews-as-apps) with:

```
deployment(zone_slug, app, kind, instance, node_name, image, port, deploy_id,
           ref, version, deployed_at, active_since, running, idle_stopped,
           deployed_by, primary key (zone_slug, app, kind, instance))
```

- `kind` ∈ `release | dev | preview`; `instance` = `''` or the PR number.
- Keep `app` as the *logical* app (name unique per zone) only if something
  still needs a row for "an app with no deployment"; otherwise the repo list is
  the source of truth, as it is now.
- Migration: copy `app` rows (those matching `-pr-<n>` become `preview`), copy
  `app_dev`, then drop the old tables in a later release once the code stops
  reading them. Do it in two versions (add + dual-read, then drop) so a rollback
  is possible.
- `Channel` becomes `DeploymentKind`; `projectSuffix()` / `subdomain()` stay as
  the single place the naming rules live. Preview project/host naming is
  unchanged.

Size: **L.** Risk: high-traffic code (`AppService`), the sweeper, wake,
`PreviewReaper`, `/deploy`, ~6 tests construct these. Mitigation: do it behind
the existing public behaviour; the Phase 0 UI work becomes the regression check.

### Phase 2 — one lifecycle service

With one table, collapse the parallel code:

- `AppService.stop/stopDev/stopIdle/stopDevIdle/start/startDev/redeploy/liveStates/devLiveStates`
  → one set of methods taking a `Deployment` key. Two thirds of that class is
  duplicated per channel today.
- Sweeper, `WakeService`, `PreviewReaper` and the status endpoint iterate
  deployments once instead of "releases, then devs, then regex the previews".
- Secrets scope and the `/deploy` response derive from `kind`; delete
  `AppService.secretScope`'s special case.

Size: **M** (mostly deletion). Deliverable: a feature added to one kind is added
to all by construction.

### Phase 3 — one UI

- A single **Deployments** table per app (repo page) and a zone-wide one (team
  page): kind, ref/version, URL, status, deployed (time, by whom), idle time,
  actions (open, restart, stop/start, delete). Same component for all three
  kinds; the row for a PR shows `#42`.
- **Per-kind settings in one place** on the app page: idle time, secrets,
  (later) resource size. Each kind shows its inherited default and an override.
- The two Release buttons / Deploy from main / `deploy-preview` label stay as
  the *triggers* — they just all end up as rows in the same table.

Size: **M.**

### Phase 4 — operational parity

Things teams and platform admins currently get for one kind or none:

- **Deployment history**: a `deployment_event` table (who/what/when/from-ref,
  success or failure) written by the one lifecycle service. Answers "what is
  running and who put it there" for all kinds. Pairs with ENVIRONMENTS-DESIGN's
  audit needs.
- **Logs**: a read-only recent-logs view per deployment (`docker logs --tail`
  on the web container) — also the quickest way to see why a deploy is dead.
- **Per-kind policy table** (platform admin, defaults overridable per app):
  default idle time (e.g. release 24h, dev 4h, preview 2h), retention after PR
  close, resource limits, max concurrent previews per app/zone, visibility.
  Replaces the single `ignition.sweep.ttl` and the hard-coded quota keys.
- **Failure surfacing**: a failed CI build or `compose up` marks the
  deployment *failed* with the reason, instead of the table silently keeping
  the previous good row (the "building…" badge already hints at this gap).

Size: **M–L**, mostly independent items that can ship one at a time.

### Phase 5 — who may do what

Align with ENVIRONMENTS-DESIGN: today any member can release. Minimum: releases
and dev deploys need a defined role; previews can stay open to whoever can
label a PR. A per-environment "may deploy / may promote" setting is that doc's
job; this plan only requires the check to exist uniformly for all three kinds.

## Decisions needed

1. **Who may release / deploy from main?** Team admin only, or members too
   (then Phase 0's check is a no-op for dev)? Previews stay open?
2. **Preview names.** Keep `<repo>-pr-<n>` inside the app-name space (40-char
   limit, collisions possible) or give previews their own namespace? Phase 1
   makes either cheap; the host name must not change.
3. **Default lifetimes** per kind (above are suggestions).
4. **Do previews count against a team's quota** and is there an open-preview
   cap? Today: yes, no cap.
5. **Existing apps:** the `deploy.yml` / `pr-preview.yml` re-seeding gap
   (CLAUDE.md, tasks 6–7) is separate but blocks anything that needs new CI
   behaviour.

## Order and checkpoints

0 → (decide 1) → 1 → 2 → 3, with Phase 4 items slotted in once Phase 2 lands.
Each phase ships on its own and leaves the system working.

**Parity checklist** (done when every cell is "yes"): listed in the team page;
status polled; start / stop / delete from the console; idle time settable and
honoured; wakes on request; secrets with a restart button; recent logs; deploy
history with who/when; role-checked trigger; lifetime governed by policy.
