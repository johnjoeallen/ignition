# Pluggable compute nodes: DinD (today) or Kubernetes — design proposal

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

The ask: let a **registered node be either kind** — the existing DinD model,
unchanged, or a Kubernetes cluster (or namespace-scoped slice of one) as an
alternative execution substrate for zone infra and apps. **Not** replacing
DinD, not requiring every deployment to run a cluster, not a rewrite —
register a node, pick its kind, everything above the node stays the same.

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

Add `kind` (`DIND` | `K8S`) and make the connection field kind-shaped instead
of always `dockerHost`:

- `DIND` (today): `dockerHost` — `unix://…` / `ssh://user@host` /
  `tcp://host:2376` (+ TLS), exactly as now.
- `K8S`: a cluster/context reference — kubeconfig context name (mounted
  kubeconfig, same pattern as today's mounted SSH key / TLS certs) or an API
  server URL + a bound service-account token. `cpus`/`memGb` stay as the
  node's *declared* capacity for `Scheduler` — v1 does **not** try to read
  live cluster capacity from `metrics-server`; that's a real improvement but
  a separate piece of work, and static declared capacity keeps both kinds
  symmetric for placement.

**Zero-schema-change spike option**: `Scheduler.place(cpu, mem, label)`
already filters by label. A K8s node could ship *today*, no `kind` column at
all, as a `DIND`-shaped node whose `labels` includes `k8s` and whose
"`dockerHost`" is actually irrelevant/unused by a K8s-only code path keyed
off that label. Fine for a spike; not the real design — overloading a
free-text label field to select an execution engine is exactly the kind of
implicit coupling CLAUDE.md's "Decisions and why" warns against elsewhere. Do
the real `kind` column first.

### 2. The seam that matters most — `NodeBackend`

Everything that currently *does* something by shelling out to `docker`
(`DockerCli`, `ComposeTemplate`, and the compose-rendering half of
`AppComposeBuilder`) sits behind a new interface, `NodeBackend`, with two
implementations:

- `DindNodeBackend` — today's code, moved behind the interface, **behavior
  unchanged**. This is a refactor of existing classes into an interface
  shape, not new logic.
- `KubernetesNodeBackend` — new.

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
the top (a `Map<Node.Kind, NodeBackend>` Spring can autowire from the two
`@Component`s). Everything upstream of that call — placement, quota
checking, `ZoneMember` bookkeeping, the Forgejo API calls, `ReleaseService`
— doesn't change.

### 3. Zone infra provisioning — `ProvisioningService`

Today: `docker compose` up Forgejo+DinD, wait healthy, register the Actions
runner, `compose cp` the generated `runner-config.yml` in, bring up the
runner. Two-phase because Forgejo needs its DB before
`forgejo-cli actions register` can produce a secret.

For `KubernetesNodeBackend`: same three logical components (Forgejo, DinD,
runner), same two-phase ordering, just K8s-shaped — a per-zone
**`Namespace`** (`zone-<slug>`, same naming discipline as today's
`zone-<slug>` compose project) holding a Forgejo `Deployment` + `PVC` +
`Service`, a DinD `Deployment` + `Service`, and a runner `Deployment`; wait
via readiness probes instead of polling `docker compose ps`; push
`runner-config.yml` as a `Secret`/`ConfigMap` mounted into the runner pod
instead of `docker compose cp`. **Deliberately not K8s-native for this part**
— see "CI isolation" below for why DinD-as-a-pod, not a from-scratch
Kubernetes-executor runner, is the right v1 scope.

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

Split it there. Step 1 stays exactly as-is, backend-agnostic, and becomes
shared code both backends call. Step 2 gets a second implementation,
`AppK8sManifestBuilder`, taking the *same* validated service tree and
emitting a `Deployment` (image, env, resource limits — already computed),
`Service`, and (for the routed service) an `Ingress` or Traefik
`IngressRoute` instead of compose YAML + Docker labels. Any declared named
volume becomes a `PVC`; any non-web sibling service (a DB/cache the app
declares) becomes its own `Deployment`+`Service`, network-isolated by the
namespace boundary instead of `pinToDefaultNetwork`'s compose-network trick.

`DindNodeBackend.deployApp` = today's `AppComposeBuilder.build()` +
`DockerCli.composeUp()`. `KubernetesNodeBackend.deployApp` =
`AppComposeBuilder`'s validation half + `AppK8sManifestBuilder` + `kubectl
apply` (or the Java K8s client — see "Client library," below).

### 5. Routing

DinD nodes: unchanged — per-node Traefik, Docker-label routing, the
controller edge reverse-proxies by `Host` over WireGuard to whichever node
is running the zone (`docs/exposure.md`; CLAUDE.md task 4, still unwired
generally — this proposal doesn't change that gap either way).

K8s nodes: Traefik's Kubernetes CRD provider (`IngressRoute`) reading the
`Ingress`/`IngressRoute` objects `AppK8sManifestBuilder` writes, doing the
same "Host → Service" hop a per-node Traefik does today from Docker labels.
The controller edge doesn't care — it's still routing to "whatever's running
`<app>.apps.<slug>.<BASE_DOMAIN>` on this node's private address," same as
now.

### 6. Teardown, quotas, idle sweep

- **Teardown**: `ZoneService.destroy` today is compose `down -v` per app
  project + the zone project + state cleanup. For K8s: `kubectl delete
  namespace zone-<slug>` is a single atomic op that takes everything with
  it — apps, PVCs, the lot. Arguably *simpler* than the DinD path, which has
  to iterate every app's own compose project by hand.
- **Quotas**: `ignition.quotas.*` (cpu-forgejo, mem-dind, cpu-app/mem-app,
  cpu-svc/mem-svc) map onto container `resources.limits` in compose today.
  Same numbers become a namespace `ResourceQuota` + per-container
  `resources.limits` on K8s — same config keys, same admin-facing knobs,
  different enforcement mechanism.
- **Idle sweep**: `IdleSweeper` reclaims a whole zone past its TTL. Backend
  call is `NodeBackend.teardownZoneInfra` either way — no change to the
  scheduling/TTL logic itself.

## CI isolation on a K8s node — the one place *not* to be K8s-native in v1

Forgejo's own Actions runner (`act_runner`) has a `docker` executor (needs a
socket — today, DinD's) and a `host` executor; it does **not** have a native
Kubernetes-Job executor the way GitLab Runner does. So "run each CI job as
its own K8s pod, no DinD at all" is a real future option but a materially
bigger, riskier change (a custom Forgejo runner label/executor, or fronting
Kaniko/Buildkit-rootless per job) — worth its own proposal later, not folded
into this one.

**v1 scope**: the DinD sidecar stays, conceptually identical, just hosted as
a K8s pod instead of a compose service — same nested-Docker-engine isolation
boundary a zone's CI gets today, same `privileged: true` requirement it
already implicitly has as a bare container. Running it in K8s needs a
narrowly-scoped `PodSecurity` admission exception (e.g. a `privileged`-level
namespace label, or a dedicated `PriorityClass`/`SecurityContext` exception)
for that one pod — **not a new class of risk**, the same power the DinD
sidecar already has today, just now inside K8s' RBAC/PSA boundary instead of
a bare Docker daemon's. State that explicitly wherever this gets reviewed —
it'll look alarming out of context.

## Isolation model, compared

| | DinD node | K8s node |
|---|---|---|
| Zone boundary | separate nested Docker engine (own netns, own image store) | `Namespace` + `NetworkPolicy` + `ResourceQuota` |
| CI build boundary | the DinD engine itself | same DinD engine, now pod-hosted (v1) |
| App-to-app-in-same-zone | compose project's private network (`pinToDefaultNetwork`) | `NetworkPolicy` scoped to the namespace |
| Blast radius of a compromised app container | node's real Docker daemon minus `AppComposeBuilder`'s transform | node's real cluster minus PSA + RBAC + the K8s equivalent transform |

Neither is strictly "more secure" — they're different primitives doing the
same job. Worth a real security pass before either goes near real teams;
today's DinD model hasn't had a from-scratch one either (see CLAUDE.md
"Known gaps" — `traefik-public` is one flat network, image blocklist only,
no CVE scanning).

## Client library

`DockerCli` shells out to the `docker` CLI on purpose (SSH-safe `compose cp`
trick, same commands the old shell scripts ran, reviewable). For K8s, the
equivalent choice is between:

- **Shell out to `kubectl`** (mirrors `DockerCli` exactly — `kubectl
  --context <ctx> apply -f -`, `kubectl delete namespace`, `kubectl wait
  --for=condition=ready`) — needs `kubectl` on the image the way `docker`
  CLI + `openssh-client` are on it today (`Dockerfile`, DESIGN.md "Image").
  Simplest to reason about, consistent with the existing "shell out, don't
  wrap" philosophy, easy to log/audit (a command line, not an SDK call).
- **`io.kubernetes:client-java`** (or `kubernetes-client/java`) — a real
  typed client, avoids `kubectl apply -f -` templating YAML-as-strings for
  every resource kind, better error surfaces than parsing `kubectl` stderr.
  Heavier dependency, a second way of doing things sitting next to
  `ProcessBuilder`-based `DockerCli`.

Lean `kubectl` for v1, consistent with "the control plane is stdlib +
Spring... compose ops shell out to the CLI" (CLAUDE.md "Conventions") and
because `AppK8sManifestBuilder` already has to produce YAML text (same
`SnakeYAML` dump pattern `AppComposeBuilder` uses) — piping that to `kubectl
apply -f -` is a one-line difference from what the code already does for
compose. Revisit a typed client if `kubectl apply -f -`'s error messages
turn out too opaque to build good console feedback from.

## Rollout — additive, DinD is still the default

1. `Node.kind` column (default `DIND` for every existing row — a one-line
   migration, no behavior change for anyone not opting in).
2. Extract `NodeBackend` from the existing `DockerCli`/`ComposeTemplate`/
   `AppComposeBuilder` call sites; ship `DindNodeBackend` as the only
   implementation. This step alone is a pure refactor — good to land and
   verify (full regression against a real node, same as DESIGN.md step 9)
   *before* any K8s code exists at all.
3. Split `AppComposeBuilder` into validate (shared) + render (backend-
   specific); `DindNodeBackend` uses the existing compose renderer, now
   under the new seam.
4. `KubernetesNodeBackend` + `AppK8sManifestBuilder`, built against a real
   test cluster (kind/k3d locally, matching DESIGN.md's own "verified
   against a live node" bar before calling any of this done).
5. Platform console: node registration form gains a kind selector +
   kind-specific fields; node kind shown in the Nodes table.
6. Zone provisioning: no new UI needed if placement is automatic — `kind` is
   a `Scheduler` placement input the same way a required `label` already is.
   A pin-to-node override (already in the provision form) works unchanged.

Nothing here requires K8s nodes to exist for DinD nodes to keep working —
steps 1-2 are worth doing regardless of whether K8s ever lands, since they
turn an implicit "everything assumes Docker" coupling into an explicit,
single-swap seam.

## Open questions

- **Cluster-per-controller-instance, or one shared cluster with a namespace
  per zone?** This doc assumes the latter (matches the "node = a host" model
  closest — a K8s "node" registration really means "a cluster/context to
  provision zone namespaces into," not literally one K8s Node object). Worth
  confirming that's actually the intended granularity before building
  `KubernetesNodeBackend` against it.
- **Multiple K8s clusters as multiple registered nodes** — `Scheduler`
  already handles this (each is just another `Node` row) — no new problem,
  flagging only because it's easy to assume "K8s support" means "one big
  cluster."
- **Live capacity vs. declared capacity** — static `cpus`/`memGb` on the
  `Node` row is a simplification either way, but it's a bigger one for a
  cluster than a single host. Worth a follow-up once this lands, not a
  blocker for v1.
- **Ingress controller assumption** — this doc assumes Traefik's Kubernetes
  CRD provider is available on a registered K8s node/cluster. If a target
  cluster doesn't run Traefik, that's either a registration prerequisite
  (document it) or `KubernetesNodeBackend` needs to also deploy Traefik into
  the cluster on first zone provision — worth deciding explicitly, not
  discovering at provision time.
- **Who ships `kind`/`k3d`/a real cluster for CI/dev testing of this path?**
  DESIGN.md's bar for "done" was a real end-to-end run against a live node;
  the K8s path needs the same against a real (even if small/local) cluster
  before it's trusted the way the DinD path is.
