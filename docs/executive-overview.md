# Executive Overview

## The claim

Large organisations run hackathons, innovation days, and internal build sprints
to surface new ideas. Most of that investment evaporates — not because the ideas
are weak, but because of what teams are handed to build on. A locked-down
corporate network won't let them install, expose, or route anything real. A
hosted cloud sandbox — even a polished one — gives back a container, not an
environment: no address of their own, no isolation to rely on, no clean way to
stand up the three or four moving parts a genuine prototype needs. Either way
the ceiling is the same, and teams hit it in the first hour. What reaches the
demo is a laptop and a slide deck.

Ignition is the layer that's missing between "here is a network" and "here is a
running product." At the push of a button, every team gets a genuinely
controlled environment that is entirely its own — its git, CI, build engine,
image registry, and routed HTTPS addresses for whatever it deploys — sealed off
from every other team by construction, with near-total freedom to build
whatever it wants inside those walls. Nobody logs into a host, manages a
separate git account, or puts a password on their app: a person's ordinary
corporate identity is the only credential, checked once at the edge. When the
event ends the whole environment is reclaimed cleanly, leaving nothing behind.

Access to what teams build is deliberate, not incidental. Every team's running
app is reachable from the corporate network — colleagues, judges, and product
owners just open the URL, no account, no VPN dance. Reaching an app from the
public internet is a separate, per-app decision: off by default, turned on one
app at a time and only with approval. The environment is controlled at every
edge; the freedom is on the inside.

The difference this makes is the difference between *innovation theatre* — a day
of slideware and localhost demos that are gone by Monday — and an *innovation
pipeline*, where every promising idea already exists as a running artifact a
product team can pick up.

That control is the point, not a constraint to be worked around. Even where
there is no policy to satisfy, an isolated disposable stack per team — with
exposure governed centrally — is still the arrangement that produces the most
working software per event: it keeps one team's mistake from becoming
everyone's outage, gives every team an identical clean start, and makes each
team's output a real running URL rather than a laptop demo. A restrictive
corporate environment doesn't create the need for this design; it just removes
the option of ignoring it.

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
Every team gets its own fully isolated stack — its own git, CI, build engine,
images, and network. One team cannot see, reach, or disrupt another — not by convention, by
construction. That gives you failure containment (one team's runaway build or
bad migration never touches another), a clean identical starting point for
everyone, and quota-fenced capacity so no team can starve the rest. It also
happens to be what lets a security team say *yes* to eighty untrusted teams
without writing eighty exceptions — but the isolation earns its keep even when
nobody's asking for it.

**2. Self-service, and delegated.**
Standing up a team is one click in the platform console and takes seconds, not
a ticket — a scheduler places it on whichever host has room. And the team
lead is their team's admin: they add teammates and create repos themselves,
through a scoped surface, without coming back to the platform team. The
marginal cost of one more team is close to zero.

**3. Real deployments, not screenshots.**
Every app a team ships is live at `https://<app>.apps.<team>.<event-domain>/`.
The team lead clicks **Release** in their team console and it builds and deploys itself;
every app is also wired to reload automatically when its image changes. One
team can run several. From the corporate network the URL just works — no
account, no per-app login — so stakeholders engage with **working software**
during judging; making an app reachable from the public internet is a separate,
per-app approval. The best ideas leave the event as a running URL and a git
repo — already deployed, already shareable — instead of a deck that needs a
project to become real.

A planned **services catalogue** extends this. A team's own infrastructure —
its database, its cache — ships in its app. The catalogue covers the
*org-standard* services teams would otherwise fake or wait on: a card-art
lookup, a rewards engine, a payments sandbox, an internal data API. One click
adds one to a team, as a blessed mock or as a *keyed proxy* to the real
service. The proxy holds an org-issued credential the platform meters and
revokes per team, so participants build against real systems without a real key
ever landing on a laptop or in a repo.

**4. Deterministic teardown.**
Every resource is namespaced per team; teardown is a single, complete
operation, and an idle sweeper reclaims teams that go quiet. The event leaves
**no residue** — no leaked containers, no creeping spend, no "what is this
from?" six months later.

## The operating posture

- **A handful of hosts, one control plane.** Register each host as a *node*;
  teams are scheduled across them by free capacity. No Kubernetes to stand up
  and staff — one small Java service, compose templates, and a few hosts.
- **Predictable footprint.** Per-team CPU and memory quotas are set in one
  place; the console shows allocation vs. capacity per node. Adding a node is
  one form.
- **No new vendor.** Forgejo (community-governed, FOSS), Docker, Traefik, and
  WireGuard — all things a platform team can already reason about and audit. No
  hosted tunnel or edge service: the controller is the only public machine.
- **Reversible.** Nothing here is a long-lived commitment. Run an event, tear it
  down, keep the templates.

## What it is, and what it is not

**It is** the environment layer for running many small teams in parallel:
isolated forge + CI + build sandbox + a routed live app per team, provisioned
and reclaimed on demand.

**It is not** a permanent internal developer platform, a replacement for
production CI/CD, or a general-purpose orchestrator. It schedules teams across
a handful of nodes for the length of an event and no more. Ideas that graduate
move onto the organisation's real platform — Ignition's job is to get them to
the point of being worth graduating.

## The bottom line

Organisations don't lack ideas from their engineers; they lack a cheap, safe,
repeatable way to let many people try many ideas at once and come away with
something real. That capability is infrastructure, and it is the constraint that
actually binds. Ignition removes it for the price of a few hosts and a handful of
scripts.
