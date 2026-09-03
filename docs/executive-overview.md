# Executive Overview

## The claim

Large organisations run hackathons, innovation days, and internal build sprints
to surface new ideas. Most of that investment leaks away — not because the ideas
are bad, but because the **infrastructure to try them doesn't scale to the
number of teams**. HackZone is the missing layer: it gives every team, at the
push of a button, a real, isolated, internet-reachable environment, and reclaims
it cleanly when the event ends.

The difference this makes is the difference between *innovation theatre* — a day
of slideware and localhost demos that are gone by Monday — and an *innovation
pipeline*, where every promising idea already has a running artifact that can be
handed to a product team.

## Why "at scale" changes the problem

One team can improvise. A senior engineer spins up a VM, installs Docker, shares
a screen. Ten teams strain that model. **Eighty teams break it**, and they break
it in ways that are individually small and collectively fatal to the event:

| Friction | What it looks like on the day |
|---|---|
| **Shared environments** | Team A's port 8080 collides with Team B's. Team C's runaway build starves everyone's CI. One broken `docker-compose` takes down a box six teams share. |
| **Manual provisioning** | A two-person platform team becomes the bottleneck for eighty teams. Setup tickets pile up. Teams lose the first afternoon — often the most creative one — to environment yak-shaving. |
| **No real "deploy"** | Demos are the presenter's laptop on a projector. Judges and stakeholders can't click anything. Nothing is shareable after the event, so nothing carries forward. |
| **Security by exception** | "Give everyone root Docker on a shared host" is not something a security team signs off for eighty unvetted participants. So either the event is watered down, or someone quietly accepts the risk. |
| **Teardown** | Resources leak. Volumes and containers from the last event are still running months later. Cloud spend creeps. The next event starts from a mess. |

Each of these is a **tax on every experiment a team runs**. Innovation
throughput is roughly:

> ideas tried  ×  fidelity of each try  ÷  friction per try

Infrastructure friction sits in the denominator and multiplies against
everything above it. Halve it and you don't get a 2× better hackathon — you get
more teams finishing, each shipping something real, and a set of demos that
still work the following week.

## What scale actually requires

HackZone is built around four properties that only matter once the team count is
large:

**1. Hard isolation, not politeness.**
Every team gets a **zone** — its own git, CI, build engine, images, and
network. One zone cannot see, reach, or disrupt another — not by convention, by
construction. This is what lets a security team say *yes* to eighty untrusted
teams without writing eighty exceptions.

**2. Self-service, and delegated.**
Standing up a zone is one command (`hz zone create <slug>`) and takes seconds,
not a ticket — a scheduler places it on whichever host has room. And the team
lead is their zone's admin: they add teammates and create repos themselves,
through a scoped surface, without coming back to the platform team. The
marginal cost of one more team is close to zero.

**3. Real deployments, not screenshots.**
Every team's app is live at `https://<team>.<event-domain>/` and redeploys on
every push. Stakeholders engage with **working software** during judging. The
best ideas leave the event as a running URL and a git repo — already deployed,
already shareable — instead of a deck that needs a project to become real.

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
  place; `hz node list` shows allocation vs. capacity. Adding a node is one
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
move onto the organisation's real platform — HackZone's job is to get them to
the point of being worth graduating.

## The bottom line

Organisations don't lack ideas from their engineers; they lack a cheap, safe,
repeatable way to let many people try many ideas at once and come away with
something real. That capability is infrastructure, and it is the constraint that
actually binds. HackZone removes it for the price of a few hosts and a handful of
scripts.
