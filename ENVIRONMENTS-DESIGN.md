# Environment ladders: gated promotion beyond dev/apps

Status: **proposal — not started**. No code changes in this doc. Written
against the codebase as of `903c47d` (see `git log -1`).

## Why

Today an app has exactly two channels, hardcoded as `Channel { DEV,
RELEASE }` (`ignition-control/.../app/Channel.java`): `DEV` —
`<name>.dev.<slug>.<BASE_DOMAIN>`, deployed by clicking "Deploy from main,"
always a fresh build of `main` HEAD — and `RELEASE` — `<name>.apps.<slug>.
<BASE_DOMAIN>`, deployed by cutting a tag (`ReleaseService.cut`, the
console's three major/minor/fix buttons), always a fresh build of that tag.

The ask: generalize that into a real **ordered ladder of named
environments** — `dev`, `stg`, `apps` (kept as-is; it's still "production,"
self-release still works exactly as it does today, per your framing) — with
an explicit **entry gate** for the first stage, a **promotion gate** for
each later transition, and a real answer to **which roles can promote
where**.

**That last part doesn't exist today at all.** Checked directly:
`ZoneConsoleController.release()` and the `/repos/dev` deploy-from-main
endpoint have **no `requireZoneAdmin()` call** — compare
`/teams/{slug}/members*`, which all call it. Every team member can already
ship straight to `.apps.` production today; the only two roles that exist
(`ZoneMember.Role { MEMBER, ZONE_ADMIN }`, plus an implicit
`PLATFORM_ADMIN`) don't distinguish "can deploy" from "can promote to
production" at all. This isn't a gap in an existing gate — there's no gate
to generalize, one needs designing from nothing.

## Current model, precisely

- **Two channels**, not a ladder — `DEV` and `RELEASE` are independent
  triggers, not adjacent rungs. Nothing "promotes" from one to the other.
- **Every channel is a rebuild, not an artifact promotion.** `DEV` rebuilds
  from `main` HEAD each time; `RELEASE` rebuilds from the tag's source each
  time. There is no "take the exact image that ran in `dev` and ship that
  same image to `apps`" — CI runs fresh, twice, independently. Worth
  naming explicitly because it's the first real decision a ladder design
  forces (see "Rebuild vs. promote," below).
- **PR previews are a third, separate mechanism** (`deploy-preview` label →
  `:pr-<n>` → `<repo>-pr-<n>.apps.<slug>.<BASE_DOMAIN>`, CLAUDE.md task 7) —
  headless, ephemeral, per-PR, deliberately ungated. Not part of the ladder;
  worth setting aside explicitly so it doesn't get pulled into this design
  by accident.
- **Roles are flat and zone-wide**: `MEMBER`, `ZONE_ADMIN` per zone
  (`ZoneMember.Role`), plus global `PLATFORM_ADMIN` (implicit zone-admin
  everywhere, `AuthorityService`). No per-app, per-environment, or
  per-action distinction beyond the handful of endpoints that explicitly
  call `requireZoneAdmin()` — member management, mainly. Release and
  deploy-from-main are not among them.

## The proposal: `Environment`, not `Channel`

Replace the fixed two-value `Channel` enum with an **ordered list of named
environments**, each carrying:

- a **subdomain label** (`dev`, `stg`, `apps`, …) — generalizes
  `Channel.subdomain()`.
- a **position** in the ladder (ordinal) — generalizes nothing today; new.
- an **entry gate** — what it takes for a deploy to land in this
  environment *at all*. For the first rung, that might be "none" (today's
  self-serve `dev`) or something real (a passing CI check, a specific
  role) — worth deciding explicitly rather than assuming "first stage is
  always free."
- a **promotion gate** — what it takes to move what's running in
  environment *N* to environment *N+1*. Almost certainly a different,
  stricter answer per transition (`dev → stg` vs. `stg → apps`), not one
  blanket rule for the whole ladder.

`Channel.subdomain()`/`projectSuffix()` generalize directly — an
`Environment` record replacing the two hardcoded arms, same naming
discipline (`app-<slug>-<name>[-<env>]` compose/stack project, K8s-target
naming per `NODE-BACKENDS-DESIGN.md` unaffected by this doc either way).

## Gates: two different questions, worth naming separately

- **Entry gate** — can *this specific deploy* land in this environment.
  Answerable today with what already exists (a role check) or with new
  primitives (a required CI check status, an explicit approval action
  recorded before the deploy proceeds).
- **Promotion gate** — moving what's *already verified running* in a lower
  environment upward. This is where "self-release still works" matters:
  the existing three-button `cut(major/minor/fix)` flow can stay exactly
  as the *trigger* for promoting into `apps`, it just needs a gate check
  added in front of it that doesn't exist today (see `release()`, above).

Both gate types should be expressible as **one of**: no gate (today's
behavior, kept as a valid choice for e.g. `dev`), a required role (see
below), a required approval count (N distinct people with a qualifying
role must approve, not just one), or a required upstream signal (CI green,
a minimum bake time in the lower environment). Start with role-based —
it's the smallest change that closes the gap you're pointing at — and
leave the others as named extension points, not a v1 requirement.

## Roles for promotion — the real gap

`MEMBER`/`ZONE_ADMIN` alone can't express "any member can push to `dev`,
only a zone admin can promote to `apps`" *and* leave room for something
finer later (a team that wants a distinct "release approver" who isn't
otherwise a full zone admin). Two shapes, in increasing order of new
surface area:

1. **Reuse `ZONE_ADMIN` as the promotion gate for the top of the ladder,
   configured per environment.** Smallest change: add the
   `requireZoneAdmin()` call `release()` is missing today, generalize it
   to "the environment this deploy targets has a configured minimum role,
   check it." No new role, no schema change beyond the environment
   definition itself carrying a `minRole`. Doesn't yet support "approver
   who isn't a full zone admin," but closes the actual hole (anyone can
   ship to production today) with the least new surface.
2. **A real per-environment permission** — e.g. `ZoneMember` gains an
   environment-scoped grant (`MEMBER`/`ZONE_ADMIN` stays the zone-wide
   role; a separate `EnvironmentApproval(zoneSlug, environment, userId)`
   table says who can promote *into* that specific environment,
   independent of their zone role). Lets a team designate release
   approvers without making them full zone admins. Real new modeling —
   worth doing once shape (1) has proven the gate mechanism works, not
   before.

Recommend shipping (1) first: it's a direct fix to a confirmed, real gap
(no gate exists today), reuses the existing role model entirely, and the
generalized "environment has a configured minimum role" mechanism is the
same shape (2) would need anyway — (2) just adds a second grant
source alongside zone role, it doesn't replace the check.

## Rebuild vs. promote

Two real models, not obviously compatible, worth deciding before building
either gate mechanism:

- **Rebuild-per-stage (today's model, kept)**: promoting to `stg` means CI
  builds fresh from wherever `stg`'s source ref points (a branch, a tag),
  same as `dev`/`apps` do today. Simple, no change to the CI contract
  (`POST /deploy` stays "here's a freshly built image"), but doesn't
  guarantee the artifact that passed `stg` is bit-for-bit what ships to
  `apps` — a rebuild between them could pick up a source change that
  landed in between, or (less likely, but real) non-reproducible build
  output.
- **Build-once, promote-the-artifact**: CI builds exactly once per release
  candidate; promotion re-tags/re-deploys that same image digest through
  later environments. Stronger "what you tested is what you ship"
  guarantee, standard practice for anything actually called "staging," but
  a real change to the CI contract — `/deploy` would need to accept "use
  this existing image" as distinct from "here's a new one," and
  `examples/deploy.yml` would need reworking, not left untouched the way
  every other change in this space has managed to leave it.

Recommend deciding this **before** implementation starts, not deferring it
— it changes the deploy contract shape, unlike the gate/role work above,
which is additive on top of what exists.

## Where the ladder lives

Per-zone default (most teams want the same three rungs), with a per-app
override plausible later (an app that's just a demo doesn't need `stg`; one
team's shared internal tool might). Start per-zone-only — per-app adds a
config surface with no clear v1 demand yet.

## Relationship to `NODE-BACKENDS-DESIGN.md`

A separate axis, deliberately: that doc is about *which node runs a
workload*; this one is about *how a deploy moves between named
environments and who's allowed to move it*. They compose rather than
depend on each other — a `stg`/`apps` split could sit entirely on DinD
nodes, or `apps` could specifically be pinned to a Kubernetes node (the
`Scheduler`'s existing label-pinning mechanism already supports "this
environment's deploys must land on a node with label X," no new placement
logic needed) while `dev`/`stg` stay on cheaper DinD capacity. Worth
flagging as a natural pairing, not folding the two docs together — one
open question each doc already raises (K8s for "production," per the
earlier discussion) points at exactly this combination.

## Open questions

- **Shape (1) vs. (2) for roles** — ship the `ZONE_ADMIN`-reuse version
  first, or hold for the real per-environment grant? Recommendation above
  is (1) first; worth confirming rather than assuming.
- **Rebuild vs. promote** — the one decision that changes the CI contract;
  needs resolving before either gate mechanism gets built, not after.
- **Does an environment's entry gate apply to the *first* deploy only, or
  every deploy into it?** ("Gate to reach the first stage" reads as
  possibly meaning "gate to onboard this app to the ladder at all" rather
  than "gate every single deploy" — worth confirming which was meant,
  since they're different features.)
- **What happens to a lower environment once something's promoted past
  it?** Left running (today's `dev`/`apps` independence, nothing torn
  down), or does promotion imply the lower rung should now match what
  just got promoted (avoiding `stg` drifting out of sync with what's
  actually in `apps`)?
- **Interaction with `dev`'s current "no gate, public, button-only"
  design** — is that still the intended shape for whatever the ladder's
  first rung ends up being, or does "entry gate to reach the first stage"
  mean that changes too?
