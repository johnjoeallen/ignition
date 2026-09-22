# Pluggable compute nodes: multiple node backends

Status: **proposal — not started**. No code changes in this doc; it's the
thing to argue over before any land. Written against the codebase as of
`903c47d` (see `git log -1`).

## Why

Today every node is the same shape: a host reachable over the `docker` CLI
(`-H <endpoint>`), running per-zone stacks as `docker compose` projects — a
`docker:dind` sidecar for isolated CI, and apps rendered from the team's own
`compose.yaml` by `AppComposeBuilder`. That's `Node`
(`ignition-control/.../node/Node.java`): `dockerHost`, `cpus`, `memGb`,
`labels`, `state`. One shape, one execution substrate.

The ask: let a **registered node be one of several kinds** — the existing
DinD model, unchanged, plus alternative execution substrates for zone infra
and apps. This doc covers three: **DinD** (today), **Kubernetes**, and
**Docker Swarm**. Not replacing DinD, not requiring every deployment to run
a cluster, not a rewrite — register a node, pick its kind, everything above
the node stays the same. The design is deliberately a *family*, not a
special case bolted on for one alternative: a fourth kind later (Nomad, a
managed container service) should cost about the same to add as Swarm does
here.

## What doesn't change, regardless of node kind

Worth stating up front, because it's most of the system: zone/app records,
`ZoneRepository`/`AppRepository`, `ForgejoClient` (Forgejo is always reached
over its own public `git.<slug>.<BASE_DOMAIN>` API — how it's hosted is
invisible to it), `ReleaseService`, the auth model
(platform/zone-admin/member, `deploy-token`/`zone-token`), the CI contract
(`POST /deploy` / `POST /undeploy`, unchanged JSON — `examples/deploy.yml`
doesn't change), quota *config* (`ignition.quotas.*`), the one
team-authored `compose.yaml` as the only artifact a team writes, and the
placement algorithm's *shape* (CPU-headroom, label-filtered). A team never
knows or cares which kind of node their zone landed on.

## The seams

Six places in the current code decide "how do we actually do this," each
already a fairly clean unit. Each gets a kind-aware split; nothing else
moves.

### 1. Node identity — `Node`

Add `kind` (`DIND` | `K8S` | `SWARM`) and make the connection field
kind-shaped instead of always meaning "a plain Docker Engine":

- `DIND` (today): `dockerHost` — `unix://…` / `ssh://user@host` /
  `tcp://host:2376` (+ TLS), exactly as now.
- `SWARM`: **also `dockerHost`, unchanged** — Swarm mode is a mode of the
  same Docker Engine (`docker swarm init` on the node once, out of band),
  reached over the exact same `-H <endpoint>` transport `DockerCli` already
  uses. No new connection shape at all; see "Docker Swarm," below, for why
  this is the cheapest of the three to actually build.
- `K8S`: a cluster/context reference — kubeconfig context name (mounted
  kubeconfig, same pattern as today's mounted SSH key / TLS certs) or an API
  server URL + a bound service-account token. Deliberately doesn't assume
  *how* the controller reaches that API server or the cluster's Traefik — a
  private/routed path (VPN, WireGuard, a corp subnet) and a directly
  publicly-reachable cluster are both valid; see "Routing," below, for what
  differs between the two.

`cpus`/`memGb` stay as the node's *declared* capacity for `Scheduler` across
all three kinds — v1 does **not** try to read live capacity from
`docker node ls`/`metrics-server`; that's a real improvement but separate
work, and static declared capacity keeps every kind symmetric for placement.

**Zero-schema-change spike option**: `Scheduler.place(cpu, mem, label)`
already filters by label. A new-kind node could ship *today*, no `kind`
column at all, as a node whose `labels` includes `k8s`/`swarm` and whose
kind-specific code path is keyed off that label. Fine for a spike; not the
real design — overloading a free-text label field to select an execution
engine is exactly the kind of implicit coupling CLAUDE.md's "Decisions and
why" warns against elsewhere. Do the real `kind` column first.

### 2. The seam that matters most — `NodeBackend`

Everything that currently *does* something by shelling out to `docker`
(`DockerCli`, `ComposeTemplate`, and the compose-rendering half of
`AppComposeBuilder`) sits behind a new interface, `NodeBackend`, with one
implementation per kind:

- `DindNodeBackend` — today's code, moved behind the interface, **behavior
  unchanged**. This is a refactor of existing classes into an interface
  shape, not new logic.
- `SwarmNodeBackend` — new, but thin (see below).
- `KubernetesNodeBackend` — new, the biggest of the three.

Rough shape (illustrative, not a commitment to exact signatures):

```
interface NodeBackend {
    ProvisionResult provisionZoneInfra(Zone zone, Node node, Quotas quotas);
    void teardownZoneInfra(Zone zone, Node node);
    DeployResult deployApp(Zone zone, Node node, String slug, String name,
                            Channel channel, String composeYaml, Map<String,String> envFile);
    void stopApp(...); void startApp(...); void undeployApp(...);
    StackStatus status(Zone zone, Node node);   // for the console's status card
}
```

`ProvisioningService`, `AppService`, `ZoneService.destroy`, the platform
console's status polling — all currently call `DockerCli`/`ComposeTemplate`
directly. They'd call `nodeBackend(node.kind())` instead, resolved once at
the top (a `Map<Node.Kind, NodeBackend>` Spring can autowire from the three
`@Component`s). Everything upstream of that call — placement, quota
checking, `ZoneMember` bookkeeping, the Forgejo API calls, `ReleaseService`
— doesn't change.

### 3. Zone infra provisioning — `ProvisioningService`

Today: `docker compose` up Forgejo+DinD, wait healthy, register the Actions
runner, `compose cp` the generated `runner-config.yml` in, bring up the
runner. Two-phase because Forgejo needs its DB before
`forgejo-cli actions register` can produce a secret.

**`SwarmNodeBackend`**: same two-phase order, same three components
(Forgejo, DinD, runner), same `zone-compose.yml.tmpl` `ComposeTemplate`
already renders — the only change is the verb: `docker -H <endpoint> stack
deploy -c <file> zone-<slug>` in place of `docker -H <endpoint> compose -p
zone-<slug> -f <file> up -d`. `compose cp`'s trick for pushing
`runner-config.yml` in doesn't exist for a stack (no long-lived named
container to `cp` into pre-deploy in the same way) — push it as a Swarm
**`config`** object (`docker config create`) mounted into the runner
service instead, or keep the current "bring up forgejo+dind, then bring up
runner separately once the config's ready" ordering and `docker cp` into
the running runner container directly (Swarm services still have real
containers underneath; `docker cp` addresses them by container ID same as
today, just found via `docker service ps` instead of a known compose
container name).

**`KubernetesNodeBackend`**: same three logical components, K8s-shaped — a
per-zone **`Namespace`** (`zone-<slug>`, same naming discipline as today's
`zone-<slug>` compose/stack project) holding a Forgejo `Deployment` + `PVC`
+ `Service`, a DinD `Deployment` + `Service`, and a runner `Deployment`;
wait via readiness probes instead of polling; push `runner-config.yml` as a
`Secret`/`ConfigMap` mounted into the runner pod.

`forgejoUuid()` derivation, the `ignition-bot` account, per-zone token
minting — all HTTP-to-Forgejo, unchanged regardless of backend.

### 4. App deployment — `AppComposeBuilder`

This is the best-shaped seam already. Its `build(slug, name, channel,
composeYaml, envFile, …)` does two logically separate things back to back:

1. **Parse + validate + transform** (`rejectTopLevel`, `rejectDisallowed`,
   `requireCleanImage`, `collectAndValidateVolumes`, `findWebService`,
   `mergeEnvFile`, `limit`) — the security boundary: exactly one
   `ignition.web: "true"` service gets routed, `privileged`/`cap_add`/host
   namespaces/binds are rejected, resource limits are forced, host `ports:`
   stripped. **All of this is policy, not Docker** — it reads and writes a
   parsed YAML `Map` tree and knows nothing about compose syntax being the
   *output* format.
2. **Render** (`putWebLabels`, `putWebNetworks`, `dump`) — turns the
   validated tree into literal `docker-compose.yml` text with Traefik
   labels.

Split it there. Step 1 stays exactly as-is, backend-agnostic, shared by all
three kinds.

- **`DindNodeBackend`**: step 2 unchanged, `DockerCli.composeUp()`.
- **`SwarmNodeBackend`**: **reuses the exact same step-2 renderer.** A Swarm
  stack file *is* a compose file — Swarm reads the same `services:`/
  `networks:`/`volumes:` structure `AppComposeBuilder` already produces,
  plus an optional `deploy:` block (replicas, resource limits, restart
  policy, placement constraints) that plain `docker compose` mostly ignores
  today. `limit()` already writes `resources.limits` in a compose-compatible
  shape; extending it to also emit `deploy.resources.limits` when the target
  is Swarm is a small addition to the same method, not a new renderer. The
  only other change is `docker stack deploy -c <file> app-<slug>-<name>` in
  place of `composeUp()`.
- **`KubernetesNodeBackend`**: needs the new `AppK8sManifestBuilder`,
  taking the *same* validated service tree and emitting a `Deployment`
  (image, env, resource limits — already computed), `Service`, and (for the
  routed service) an `Ingress`/`IngressRoute` instead of compose YAML +
  Docker labels. A declared named volume becomes a `PVC`; a non-web sibling
  service becomes its own `Deployment`+`Service`, isolated by the namespace
  boundary instead of `pinToDefaultNetwork`'s compose-network trick.

Swarm, notably, needs **no new renderer at all** — it's the same output
format Docker already understands two ways (`compose up` vs. `stack
deploy`). This is the concrete reason Swarm is the cheapest third kind to
actually implement, not just to design.

### 5. Routing

Traefik is the thing that actually terminates HTTPS and routes `Host` traffic
to a running app deployment, for every kind. What varies is **where in the
chain it does that**, and that's a per-node network-reachability fact, not a
fixed architecture:

- **Node only reachable via a private/routed path** (a VPN, a corp-routed
  subnet, cloud VPC peering, WireGuard — `docs/exposure.md`'s controller
  edge model is one instance of this, itself marked "one proposed model,
  draft," not a requirement): that node's own Traefik stays internal,
  plain HTTP, and something in front of it — the controller edge, in the
  `exposure.md` model — is the thing that actually holds the cert and
  terminates HTTPS, reverse-proxying by `Host` over whatever the private
  path is.
- **Node/cluster is itself publicly reachable**: its own Traefik can
  terminate TLS directly, on its own DNS name, with no requirement to
  route everything through one shared edge first. Equally valid — which
  topology applies is recorded per node at registration (CLAUDE.md task 4
  covers making the *edge* model real; it isn't the only model this design
  needs to support).

The label/CRD vocabulary a given kind's Traefik reads is unaffected by
either topology — only whether that Traefik has a cert and a public
listener, or sits behind something else that does:

- **DinD** (unchanged): per-node Traefik, Docker-label routing.
- **Swarm**: Traefik's **Swarm provider** (`providers.swarm`, sometimes
  called Swarm mode) reads service labels cluster-wide via the Docker API in
  swarm mode, rather than per-container labels on one host — same label
  vocabulary `putWebLabels` already writes, a different Traefik provider
  config on that node's Traefik instance. `AppComposeBuilder`'s label output
  doesn't need to change; only the per-node Traefik's *own* static config
  does, once, at node registration.
- **K8s**: Traefik's Kubernetes CRD provider (`IngressRoute`) reading the
  `Ingress`/`IngressRoute` objects `AppK8sManifestBuilder` writes — k3s ships
  this by default.

### 6. Teardown, quotas, idle sweep

- **Teardown**: DinD today is compose `down -v` per app project + the zone
  project + state cleanup. **Swarm**: `docker stack rm zone-<slug>` /
  `docker stack rm app-<slug>-<name>` — same granularity as today (per
  stack, not a single cluster-wide op), volumes need the same explicit
  handling compose `down -v` gives (`docker stack rm` does **not** remove
  named volumes by default — flag this explicitly in whatever teardown code
  lands, it's an easy silent-data-left-behind bug). **K8s**: `kubectl delete
  namespace zone-<slug>` is a single atomic op that takes everything with
  it, including volumes — the one place K8s is structurally simpler than
  either compose or Swarm.
- **Quotas**: `ignition.quotas.*` map onto `resources.limits` in compose
  today; the same numbers become `deploy.resources.limits` in a Swarm
  service (enforced by the engine same as compose limits) or a namespace
  `ResourceQuota` + per-container `resources.limits` on K8s — same config
  keys, same admin-facing knobs, different enforcement mechanism per kind.
- **Idle sweep**: `IdleSweeper` reclaims a whole zone past its TTL. Backend
  call is `NodeBackend.teardownZoneInfra` regardless of kind — no change to
  the scheduling/TTL logic itself.

## CI isolation on a non-DinD-shaped node

Forgejo's own Actions runner (`act_runner`) has a `docker` executor (needs a
socket — today, DinD's) and a `host` executor; it does **not** have a native
Kubernetes-Job executor the way GitLab Runner does, and no native
Swarm-service-per-job executor either. So "run each CI job as its own
pod/service, no DinD at all" is a real future option for either kind but a
materially bigger, riskier change (a custom runner executor, or fronting
Kaniko/Buildkit-rootless per job) — worth its own proposal later, not folded
into this one.

**v1 scope, both Swarm and K8s**: the DinD sidecar stays, conceptually
identical, just hosted as a Swarm service or a K8s pod instead of a plain
compose service — same nested-Docker-engine isolation boundary a zone's CI
gets today, same `privileged: true` requirement it already implicitly has as
a bare container. On K8s this needs a narrowly-scoped `PodSecurity`
admission exception for that one pod; on Swarm, `privileged` is a normal
(if uncommon) service option, no cluster-wide policy layer to work around —
**one more reason Swarm is the lower-friction addition**. Neither is a new
class of risk — the same power the DinD sidecar already has today, just
inside a different cluster boundary. State that explicitly wherever this
gets reviewed — it'll look alarming out of context.

## Backend TLS (Traefik → app) — not just the edge

Today's explicit choice (CLAUDE.md, `docs/exposure.md`): "behind the edge
everything is plain HTTP — the private link is the confidentiality
boundary." That's a real decision, not an oversight, but it means anyone who
can reach the private segment — a compromised node, lateral movement inside
a shared cluster or swarm, a misrouted packet — sees plaintext between
Traefik and every app. Worth closing for real production use, and worth
treating as a **per-app, deploy-time choice** — same shape as `visibility`
(`corp`/`public`/`private`, CLAUDE.md task 4) — not a node-wide or
platform-wide setting, since different teams' apps will have different
answers to "do we already have a cert to bring."

Two modes, at deploy time:

- **Certless** — the team supplies nothing; the backend hop gets encrypted
  (or the equivalent guarantee) without the team provisioning or managing
  any certificate. What actually delivers that varies a lot by kind — see
  below, since for one kind it's a real cert the app never sees, and for
  another there's no cert anywhere at all.
- **App-provided cert** — the team supplies their own cert+key (their own
  PKI, an internal CA, whatever they already run), their app serves HTTPS
  with it directly, and Traefik's backend transport for *that specific app*
  is configured to trust it (pin the cert, or trust the issuing CA) rather
  than doing plaintext. The natural fit for a team that already has
  certificate lifecycle management and wants it end-to-end, or is deploying
  behind their own compliance requirement.

### Ignition as its own CA — one issuance mechanism, three delivery paths

Rather than three unrelated certless mechanisms, one design covers all
three kinds: **`ignition-control` holds an internal CA** — same custody
model as `IGN_SECRET_KEY` and the `zone-token`/`deploy-token` it already
mints and holds — and, for any app in certless mode, mints a short-lived
leaf cert at deploy time. SAN = the app's real hostname
(`<app>.apps.<slug>.<BASE_DOMAIN>`), so it validates exactly like a normal
cert would; no app-visible difference from a "real" one.

**Issuance and trust are kind-agnostic; only delivery differs**, and
delivery was already going to differ per kind for other things in this doc
(runner config, `.env`):

- **DinD**: leaf cert+key written as sibling files next to the rendered
  compose file (same pattern `ComposeTemplate` already uses for files on
  disk), bind-mounted in by `AppComposeBuilder`'s transform.
- **Swarm**: a `docker secret` — Swarm's native encrypted-at-rest secret
  distribution, alongside the `configCreate`-style additions already
  proposed for runner config.
- **K8s**: a `Secret`, created by `KubernetesNodeBackend` the same way it'd
  already create one for `runner-config.yml`.

Traefik's side is identical everywhere: trust the Ignition CA's public root
**once, per node, at registration** — not per app. That's what makes this
unify cleanly where the three separate answers didn't.

**What this replaces**: K8s no longer needs a service mesh for real
per-app TLS — that was the heaviest ask in the earlier draft of this
section, and it turns out to be unnecessary once Ignition is willing to be
a CA. A mesh becomes a genuine *upgrade* later (automatic pod-to-pod mTLS
beyond just the Traefik hop, rotation Ignition doesn't have to drive
itself), not the only path to "real." Swarm keeps its network-encryption
option (`--opt encrypted` — cheapest, no cert anywhere) *and* gains this as
a second option when a team wants actual app-level identity, not just wire
encryption. DinD goes from "the weakest of the three" to symmetric with the
other two — same mechanism, just a bind-mounted file instead of a secret
object.

**Clean upgrade to mutual TLS**, once this exists: issue Traefik itself a
client cert from the same CA, configure the app side to require+verify it.
That's "mint one more cert" on infrastructure that already exists, not a
new architecture — a natural v2 increment, not v1 scope.

**App-provided** stays available everywhere as the alternative for a team
that already has its own PKI/cert lifecycle and wants end-to-end control —
Traefik's per-app transport trusts whatever they bring instead of the
Ignition CA.

**New, real questions this specific design introduces** (beyond the
generic ones already in "Open questions," below):

- **Root CA key custody and rotation.** Everything else chains to this key
  — compromise it and every app cert issued under it is suspect. Needs the
  same seriousness as `IGN_SECRET_KEY` custody already gets, not an
  afterthought because certs feel like a smaller thing than tokens.
- **Leaf lifetime: 1-7 days, decided.** Short enough that revocation is
  rarely needed (the common pragmatic answer for this class of internal
  PKI — no CRL/OCSP infrastructure proposed), long enough that a
  restart-triggered pickup (below) isn't disruptively frequent. A platform
  config knob in the same family as `ignition.quotas.*` —
  `ignition.tls.leaf-cert-ttl`, default somewhere in the range, e.g. `3d` —
  not a hardcoded constant.
- **Rotation is a `@Scheduled` job**, the same shape as `IdleSweeper` /
  `PreviewReaper`: re-mint and re-deliver before expiry. Trigger it as a
  **fraction of the configured lifetime elapsed** (e.g. at 60%), not a fixed
  absolute margin — a fixed "rotate 24h before expiry" breaks at the short
  end of a 1-7 day range (negative margin on a 1-day cert); a fraction
  scales correctly across whatever `leaf-cert-ttl` is actually set to.
- **Leaf rotation without a restart, per kind — softened by the lifetime.**
  A K8s `Secret` update can trigger a re-mount an app can watch for; a
  bind-mounted file on DinD needs the app to re-read it. At day-scale
  lifetimes rather than hours, **a restart-triggered pickup is an
  acceptable v1 answer everywhere** — rotation already has to run
  something like weekly-at-worst, and a rolling restart at that cadence is
  a small ask. True hot-reload (no restart, any kind) is a nice-to-have to
  revisit later, not a v1 requirement anymore given the chosen lifetime.

This gap already exists for today's DinD-only deployment, not just the two
proposed additions — CLAUDE.md's "Known gaps" already lists `traefik-public`
being one flat network as an open risk; backend TLS is the same category of
gap (confidentiality, not just segmentation) and belongs on that same list,
independent of whether Swarm/K8s ever land.

## Isolation model, compared

| | DinD node | Swarm node | K8s node |
|---|---|---|---|
| Zone boundary | separate nested Docker engine (own netns, own image store) | Swarm overlay network per stack + `docker config`/`secret` scoping | `Namespace` + `NetworkPolicy` + `ResourceQuota` |
| CI build boundary | the DinD engine itself | same DinD engine, now Swarm-service-hosted (v1) | same DinD engine, now pod-hosted (v1) |
| App-to-app-in-same-zone | compose project's private network (`pinToDefaultNetwork`) | stack's own overlay network (same shape, cluster-wide instead of host-local) | `NetworkPolicy` scoped to the namespace |
| Blast radius of a compromised app container | node's real Docker daemon minus `AppComposeBuilder`'s transform | the whole swarm's real Docker daemons minus the transform — **wider than DinD/K8s by default**, since a Swarm service can in principle be scheduled onto *any* manager/worker in the cluster, not just "this node" | node's real cluster minus PSA + RBAC + the K8s equivalent transform |
| Traefik → app traffic confidentiality (see "Backend TLS," above) | plain HTTP by default; certless = Ignition-CA leaf cert, delivered as a bind-mounted file | plain HTTP by default; certless = `--opt encrypted` overlay (no cert, cheapest) **or** an Ignition-CA leaf cert via `docker secret` for real app identity | plain HTTP by default; certless = Ignition-CA leaf cert via a `Secret` — no mesh required; a mesh is an optional later upgrade, not a v1 requirement |

That last row is the one genuinely new risk Swarm introduces relative to the
other two: a single-node swarm (`docker swarm init` with no additional
workers joined) has the same blast radius as a plain DinD node, but a
multi-node swarm spreads a zone's containers across hosts unless placement
constraints pin them — worth deciding explicitly (pin every Ignition
service to the manager, or accept cluster-wide placement) rather than
discovering it once a real multi-node swarm is registered. None of the three
kinds has had a from-scratch security pass yet either way (see CLAUDE.md
"Known gaps" — `traefik-public` is one flat network, image blocklist only,
no CVE scanning).

## Client library / tooling

`DockerCli` shells out to the `docker` CLI on purpose (SSH-safe `compose cp`
trick, same commands the old shell scripts ran, reviewable).

- **`SwarmNodeBackend`** needs **no new tooling at all** — `docker` CLI is
  already on the image (`Dockerfile`), `DockerCli.docker(dockerHost, args)`
  already runs arbitrary subcommands against a remote endpoint. It needs a
  handful of new thin methods alongside the existing `compose(...)` ones
  (`stackDeploy`, `stackRm`, `stackPs`, `configCreate`) — additive to
  `DockerCli`, not a new class of dependency.
- **`KubernetesNodeBackend`** is the one real tooling decision: shell out to
  `kubectl` (mirrors `DockerCli` exactly, needs `kubectl` added to the image
  the way `docker` CLI + `openssh-client` are today) vs. a typed client
  (`io.kubernetes:client-java`). Lean `kubectl` for v1, consistent with "the
  control plane is stdlib + Spring... compose ops shell out to the CLI"
  (CLAUDE.md "Conventions") and because `AppK8sManifestBuilder` already has
  to produce YAML text the same way `AppComposeBuilder` does — piping that
  to `kubectl apply -f -` is a one-line difference from what the code
  already does for compose. Revisit a typed client if `kubectl`'s error
  messages turn out too opaque to build good console feedback from.

## Rollout — additive, DinD is still the default

1. `Node.kind` column (default `DIND` for every existing row — a one-line
   migration, no behavior change for anyone not opting in).
2. Extract `NodeBackend` from the existing `DockerCli`/`ComposeTemplate`/
   `AppComposeBuilder` call sites; ship `DindNodeBackend` as the only
   implementation. This step alone is a pure refactor — good to land and
   verify (full regression against a real node, same as DESIGN.md step 9)
   *before* any second kind exists at all.
3. Split `AppComposeBuilder` into validate (shared) + render
   (backend-specific, still compose-shaped for both DinD and Swarm);
   `DindNodeBackend` uses the existing renderer, now under the new seam.
4. **`SwarmNodeBackend`** — reuses the compose renderer as-is, adds the
   `stack deploy`/`stack rm` `DockerCli` methods, adds the Swarm-provider
   Traefik config for a node registered as that kind. Smallest of the two
   new kinds; a reasonable candidate to land *before* Kubernetes support,
   validating the `NodeBackend` seam against a second real implementation
   without also taking on a new manifest format or tooling decision.
5. **`KubernetesNodeBackend`** + `AppK8sManifestBuilder`, built against a
   real test cluster (kind/k3d locally, matching DESIGN.md's own "verified
   against a live node" bar before calling any of this done).
6. Platform console: node registration form gains a kind selector +
   kind-specific fields; node kind shown in the Nodes table.
7. Zone provisioning: no new UI needed if placement is automatic — `kind` is
   a `Scheduler` placement input the same way a required `label` already is.
   A pin-to-node override (already in the provision form) works unchanged.

Nothing here requires a second or third kind to exist for DinD nodes to keep
working — steps 1-3 are worth doing regardless of how many kinds eventually
land, since they turn an implicit "everything assumes Docker" coupling into
an explicit, single-swap seam.

## Open questions

- **Cluster-per-controller-instance, or one shared cluster/swarm with a
  namespace/stack-prefix per zone?** This doc assumes the latter for both
  Swarm and K8s (matches the "node = a host" model closest — a "K8s node" or
  "Swarm node" registration really means "a cluster/context to provision
  zone namespaces/stacks into," not literally one machine). Worth confirming
  that's the intended granularity before building either backend.
- **Multiple clusters/swarms as multiple registered nodes** —
  `Scheduler` already handles this (each is just another `Node` row) — no
  new problem, flagging only because it's easy to assume "cluster support"
  means "one big cluster."
- **Swarm's wider default blast radius** (see the isolation table) — decide
  the placement-constraint policy before registering any multi-node swarm.
- **Live capacity vs. declared capacity** — static `cpus`/`memGb` on the
  `Node` row is a simplification for all three kinds, but a bigger one for a
  cluster than a single host. Worth a follow-up once this lands, not a
  blocker for v1.
- **Ingress controller assumption on K8s** — this doc assumes Traefik's
  Kubernetes CRD provider is available on a registered K8s node/cluster. If
  a target cluster doesn't run Traefik, that's either a registration
  prerequisite (document it) or `KubernetesNodeBackend` needs to also deploy
  Traefik into the cluster on first zone provision.
- **Who ships test infra for this** — a real Swarm (even `docker swarm
  init` on a single dev VM) and a real small K8s cluster (`kind`/`k3d`) for
  CI/dev testing of each new path. DESIGN.md's bar for "done" was a real
  end-to-end run against a live node; every new kind needs the same before
  it's trusted the way the DinD path is.
- **Backend TLS scope for v1** — is plain HTTP behind Traefik (today's
  status quo, "the private link is the confidentiality boundary")
  acceptable to ship for a first Swarm/K8s backend, with the Ignition-CA
  certless mode and app-provided as explicit fast-follow work? Or is one
  mode a hard requirement before any production use, in which case it needs
  to move from "Open questions" into the actual v1 scope for that kind.
  Worth deciding per kind and per mode, not as one blanket answer — Swarm's
  network-encryption certless mode is cheap enough it may as well be in
  from the start regardless of what's decided for the CA-backed mode
  everywhere else.
- **Ignition-as-CA is accepted in principle** (see "Ignition as its own
  CA," above), leaf lifetime decided (1-7 days, `ignition.tls.leaf-cert-ttl`,
  rotation on a `@Scheduled` job at a fraction elapsed, restart-triggered
  pickup acceptable for v1). What's still genuinely open is **root CA key
  custody and rotation** — the one piece nothing above resolves, and the
  one that matters most (compromise it and every leaf cert issued under it
  is suspect).
