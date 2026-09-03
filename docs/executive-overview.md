# Executive Overview

## The claim

Large organisations run hackathons, innovation days, and internal build sprints
to surface new ideas. Most of that investment leaks away — not because the ideas
are bad, but because the **infrastructure to try them doesn't scale to the
number of teams**. Ignition is the missing layer: it gives every team, at the
push of a button, a real, isolated, internet-reachable environment, and reclaims
it cleanly when the event ends.

The difference this makes is the difference between *innovation theatre* — a day
of slideware and localhost demos that are gone by Monday — and an *innovation
pipeline*, where every promising idea already has a running artifact that can be
handed to a product team.

This is not primarily a story about corporate restrictions. Even with root on
every host, no security review, and budget to spare, an isolated disposable
stack per team is still the model that produces the most working software per
event — for reasons of failure containment, coordination cost, fidelity, and
hygiene that have nothing to do with policy. Restrictive environments simply
make the same design non-optional.

## Why a shared environment is the wrong default

Put the eighty teams on a shared pool of boxes — generously provisioned, no
access controls in the way — and the event still degrades, for structural
reasons:

- **Failure isolation.** Hackathon code is code nobody has run before:
  half-finished migrations, an accidental fork bomb, a runaway build, a process
  that eats all the memory. On shared infrastructure one team's mistake is
  every team's outage, and recovery means finding who has root and what they
  changed. Contained to one stack, it's a two-minute private problem.
- **Coordination cost.** Shared infrastructure forces teams to negotiate —
  ports, base images, "can I restart this", "why is CI slow", "who's using the
  accelerator". Each negotiation is a synchronous interruption to someone
  else's creative flow, and there are O(teams²) of them.
- **Contaminated starting state.** Without a clean per-team baseline you get
  "works because I installed something yesterday" and cruft left over from the
  last event. Debugging *shared* state is nobody's idea of a hackathon.
- **Fidelity.** A shared box tends to produce localhost demos. What carries
  forward from an event is a running, routable URL and a repo — the demo has to
  *be* the artifact, which means every team needs a real deployment surface.
- **Teardown.** Shared resources leak. Volumes and containers outlive the
  event; the next one starts from a mess; spend creeps. Even with money to
  burn, undead infrastructure is a reliability and trust problem.

## Why "at scale" makes it acute

One team can improvise around all of the above. A senior engineer spins up a
VM, installs Docker, shares a screen. Ten teams strain that. **Eighty teams
break it**, and they break it in ways that are individually small and
collectively fatal to the event:

| Friction | What it looks like on the day |
|---|---|
| **Contention compounds** | Every structural problem above happens ~80 times at once. Team A's port 8080 collides with Team B's; Team C's runaway build starves everyone's CI; one broken `docker-compose` takes down a box six teams share — on the one day nobody has slack to firefight. |
| **Manual provisioning** | A two-person platform team becomes the bottleneck for eighty teams. Setup tickets pile up. Teams lose the first afternoon — often the most creative one — to environment yak-shaving. |
| **No real "deploy"** | Demos are the presenter's laptop on a projector. Judges and stakeholders can't click anything. Nothing is shareable after the event, so nothing carries forward. |
| **Security by exception** | "Give everyone root Docker on a shared host" is not something a security team signs off for eighty unvetted participants. So either the event is watered down, or someone quietly accepts the risk. |
| **Teardown at eighty** | Cleaning up one hand-built environment is a chore; cleaning up eighty never fully happens. Volumes and containers from the last event are still running months later; the next one starts from a mess. |

Each of these is a **tax on every experiment a team runs**. Innovation
throughput is roughly:

> ideas tried  ×  fidelity of each try  ÷  friction per try

Infrastructure friction sits in the denominator and multiplies against
everything above it. Halve it and you don't get a 2× better hackathon — you get
more teams finishing, each shipping something real, and a set of demos that
still work the following week.

## What scale actually requires

Ignition is built around four properties that only matter once the team count is
large:

**1. Hard isolation, not politeness.**
Every team gets a **zone** — its own git, CI, build engine, images, and
network. One zone cannot see, reach, or disrupt another — not by convention, by
construction. That gives you failure containment (one team's runaway build or
bad migration never touches another), a clean identical starting point for
everyone, and quota-fenced capacity so no team can starve the rest. It also
happens to be what lets a security team say *yes* to eighty untrusted teams
without writing eighty exceptions — but the isolation earns its keep even when
nobody's asking for it.

**2. Self-service, and delegated.**
Standing up a zone is one command (`ign zone create <slug>`) and takes seconds,
not a ticket — a scheduler places it on whichever host has room. And the team
lead is their zone's admin: they add teammates and create repos themselves,
through a scoped surface, without coming back to the platform team. The
marginal cost of one more team is close to zero.

**3. Real deployments, not screenshots.**
Every app a team ships is live at `https://<app>.apps.<team>.<event-domain>/`.
The team lead clicks **Release** in their zone console and it builds and deploys itself;
every app is also wired to reload automatically when its image changes. One
team can run several. Stakeholders engage with **working software** during
judging. The best ideas leave the event as a running URL and a git repo —
already deployed, already shareable — instead of a deck that needs a project to
become real.

**4. Deterministic teardown.**
Every resource is namespaced per zone; teardown is a single, complete
operation, and an idle sweeper reclaims zones that go quiet. The event leaves
**no residue** — no leaked containers, no creeping spend, no "what is this
from?" six months later.

## The operating posture

- **A handful of hosts, one control plane.** Register each host as a *node*;
  zones are scheduled across them by free capacity. No Kubernetes to stand up
  and staff — shell scripts, compose templates, and one small control service.
- **Predictable footprint.** Per-zone CPU and memory quotas are set in one
  place; `ign node list` shows allocation vs. capacity. Adding a node is one
  command.
- **No new vendor.** Forgejo (community-governed, FOSS), Docker, and Traefik —
  all things a platform team can already reason about and audit.
- **Reversible.** Nothing here is a long-lived commitment. Run an event, tear it
  down, keep the scripts.

## What it is, and what it is not

**It is** the environment layer for running many small teams in parallel:
isolated forge + CI + build sandbox + a routed live app per team, provisioned
and reclaimed on demand.

**It is not** a permanent internal developer platform, a replacement for
production CI/CD, or a general-purpose orchestrator. It schedules zones across
a handful of nodes for the length of an event and no more. Ideas that graduate
move onto the organisation's real platform — Ignition's job is to get them to
the point of being worth graduating.

## The bottom line

Organisations don't lack ideas from their engineers; they lack a cheap, safe,
repeatable way to let many people try many ideas at once and come away with
something real. That capability is infrastructure, and it is the constraint that
actually binds. Ignition removes it for the price of a few hosts and a handful of
scripts.
