package net.dublinux.ignition.app;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import net.dublinux.ignition.docker.DockerCli;
import net.dublinux.ignition.node.NodeRepository;
import net.dublinux.ignition.provisioning.ProvisioningService;
import net.dublinux.ignition.templates.RenderService;
import net.dublinux.ignition.zone.Zone;
import net.dublinux.ignition.zone.ZoneRepository;
import net.dublinux.ignition.zone.ZoneService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Deployed-app views and the CI bridge. The app is rendered from
 * {@code app-compose.tmpl} and applied on the zone's node (not inside its DinD
 * sandbox), as compose project {@code app-<slug>-<name>}.
 */
@Service
public class AppService {

    private static final Logger log = LoggerFactory.getLogger(AppService.class);

    private static final Pattern NAME = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$");
    private static final DateTimeFormatter DEPLOY_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final AppRepository apps;
    private final DevDeploymentRepository devApps;
    private final ZoneRepository zones;
    private final ZoneService zoneService;
    private final NodeRepository nodes;
    private final DockerCli docker;
    private final RenderService render;
    private final AppComposeBuilder composeBuilder;
    private final AppHealthProbe health;
    private final AppSecretService secrets;

    /** Zones whose registry login has succeeded since this process started. */
    private final Set<String> loggedIn = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean loginRunning = new AtomicBoolean();

    /** How long one node's container snapshot is reused — the console polls every 5s per open page. */
    private static final long SNAPSHOT_TTL_MS = 4_000;
    /** One compose project's containers: all running and none unhealthy, plus its health-path label. */
    private record Proj(boolean up, String healthPath) {}
    private record Snapshot(long at, Map<String, Proj> byProject) {}
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    public AppService(AppRepository apps, DevDeploymentRepository devApps, ZoneRepository zones,
                      ZoneService zoneService, NodeRepository nodes, DockerCli docker,
                      RenderService render, AppComposeBuilder composeBuilder, AppHealthProbe health,
                      AppSecretService secrets) {
        this.apps = apps;
        this.devApps = devApps;
        this.zones = zones;
        this.zoneService = zoneService;
        this.nodes = nodes;
        this.docker = docker;
        this.render = render;
        this.composeBuilder = composeBuilder;
        this.health = health;
        this.secrets = secrets;
    }

    public List<DeployedApp> list() {
        return apps.findAllOrdered();
    }

    public List<DeployedApp> listForZone(String slug) {
        return apps.findByZone(slug);
    }

    public record DeployResult(String zone, String app, String deployId, String url) {}

    /** {@code POST /deploy} on the release channel, {@code main}. */
    public DeployResult deploy(String slug, String name, String image, int port) {
        return deploy(slug, name, image, port, Channel.RELEASE, "main", name);
    }

    /**
     * {@code POST /deploy} — bearer already resolved to {@code slug}. The
     * channel picks the host ({@code .apps.} / {@code .dev.}), the compose
     * project, and which table the deployment is recorded in. {@code sourceRepo}
     * is the Forgejo repo to read {@code compose.yaml} / {@code .env} from at
     * {@code ref} — normally the same as {@code name}, but a PR preview deploys
     * as {@code <repo>-pr-<n>} from the {@code <repo>} repo. No compose file → a
     * synthesised single-service deploy.
     */
    public DeployResult deploy(String slug, String name, String image, int port,
                               Channel channel, String ref, String sourceRepo) {
        Zone z = zones.find(slug)
                .orElseThrow(() -> new IllegalArgumentException("no such zone: " + slug));
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "app name must be [a-z0-9-], 1–40 chars, no leading/trailing dash"
                    + (name.length() > 40 ? " (this one is " + name.length() + ")" : ""));
        }
        String registry = z.gitHost();
        if (!image.startsWith(registry + "/")) {
            throw new IllegalArgumentException("image must be from " + registry + "/");
        }
        String repo = (sourceRepo == null || sourceRepo.isBlank()) ? name : sourceRepo;

        String project = "app-" + slug + "-" + name + channel.projectSuffix();
        String deployId = DEPLOY_ID.format(Instant.now());

        String repoYaml = zoneService.appCompose(slug, repo, ref).orElse(null);
        String repoEnv = zoneService.appEnv(slug, repo, ref).orElse(null);
        String rendered;
        try {
            rendered = composeBuilder.build(slug, name, channel, image, port, repoYaml, repoEnv,
                    secrets.forApp(slug, repo, secretScope(channel, name, repo)));
        } catch (ComposeSpecException e) {
            log.warn("deploy {}/{} [{}]: compose.yaml rejected — {}", slug, name, channel, e.getMessage());
            throw e;
        }
        Path composeFile = render.writeAppCompose(slug, name, channel, rendered);
        log.info("deploy {}/{} [{}]: image={} port={} ref={} compose={} env={} -> node {}",
                slug, name, channel, image, port, ref, repoYaml == null ? "synthesised" : "from repo",
                repoEnv == null ? "none" : "from repo", z.node());

        registryLogin(z);

        DockerCli.Result r = docker.composeUp(dockerHost(z), project, composeFile.toString());
        if (!r.ok()) {
            String detail = firstLine(r.stderr());
            log.warn("deploy {}/{} [{}]: compose up failed: {}", slug, name, channel, detail);
            throw new DeployException("compose up failed: " + detail);
        }
        log.info("deploy {}/{} [{}]: up (deploy_id {})", slug, name, channel, deployId);

        if (channel == Channel.DEV) {
            DevDeployment dev = devApps.findByZoneAndName(slug, name).orElse(null);
            if (dev == null) {
                dev = new DevDeployment(slug, name, z.node(), image, port, deployId);
            } else {
                dev.update(z.node(), image, port, deployId);
            }
            devApps.save(dev);
        } else {
            DeployedApp app = apps.findByZoneAndName(slug, name).orElse(null);
            if (app == null) {
                app = new DeployedApp(slug, name, z.node(), image, port, deployId);
            } else {
                app.update(z.node(), image, port, deployId);
            }
            apps.save(app);
        }
        touch(z);

        return new DeployResult(slug, name, deployId,
                "https://%s.%s.%s.%s/".formatted(name, channel.subdomain(), slug, z.baseDomain()));
    }

    /** {@code POST /undeploy} — release channel. */
    public void undeploy(String slug, String name) {
        undeploy(slug, name, Channel.RELEASE);
    }

    /**
     * Tear down one channel of an app — every container, network and named
     * volume in the compose project. {@code docker compose -p <project> down -v}
     * with <em>no</em> {@code -f}: compose reconstructs the whole project from
     * the running resources' labels, so a stale/absent rendered file can't
     * leave a multi-service app's DB volume behind.
     */
    public void undeploy(String slug, String name, Channel channel) {
        boolean known = channel == Channel.DEV
                ? devApps.findByZoneAndName(slug, name).isPresent()
                : apps.findByZoneAndName(slug, name).isPresent();
        if (!known) {
            throw new IllegalArgumentException("zone " + slug
                    + (channel == Channel.DEV ? " has no dev deployment of '" : " has no app '") + name + "'");
        }
        Zone z = zones.find(slug).orElse(null);
        docker.compose(dockerHost(z), "app-" + slug + "-" + name + channel.projectSuffix(),
                null, "down", "-v", "--remove-orphans");
        if (channel == Channel.DEV) {
            devApps.deleteByZoneAndName(slug, name);
        } else {
            apps.deleteByZoneAndName(slug, name);
        }
        if (z != null) {
            touch(z);
        }
    }

    /**
     * Stop a release app's containers without tearing the stack down —
     * {@code docker compose -p <project> -f <compose> stop}. Networks and named
     * volumes stay; the row stays, flipped to not-running. Idempotent: stopping
     * an already-stopped app is a no-op. {@link #start} brings it back.
     */
    public void stop(String slug, String name) {
        DeployedApp app = apps.findByZoneAndName(slug, name)
                .orElseThrow(() -> new IllegalArgumentException("zone " + slug + " has no app '" + name + "'"));
        lifecycle(slug, name, Channel.RELEASE, "stop");
        app.markStopped();
        apps.save(app);
    }

    /** Restart a stopped release app — {@code docker compose -p <project> -f <compose> start}. Idempotent. */
    public void start(String slug, String name) {
        DeployedApp app = apps.findByZoneAndName(slug, name)
                .orElseThrow(() -> new IllegalArgumentException("zone " + slug + " has no app '" + name + "'"));
        lifecycle(slug, name, Channel.RELEASE, "start");
        app.markRunning();
        apps.save(app);
    }

    /** Which secrets a deployment gets: dev gets dev's; a PR preview ({@code <repo>-pr-<n>}) gets the preview set; else release. */
    static SecretScope secretScope(Channel channel, String name, String repo) {
        if (channel == Channel.DEV) {
            return SecretScope.DEV;
        }
        return repo.equals(name) ? SecretScope.RELEASE : SecretScope.PREVIEW;
    }

    /**
     * Redeploy one environment of an app with the image and port it already
     * runs, so changed secrets reach its containers without cutting a release.
     * {@link SecretScope#PREVIEW} redeploys every PR preview of the app.
     */
    public void redeploy(String slug, String name, SecretScope scope) {
        switch (scope) {
            case RELEASE -> {
                DeployedApp a = apps.findByZoneAndName(slug, name)
                        .orElseThrow(() -> new IllegalArgumentException(name + " has no release deployed"));
                deploy(slug, name, a.image(), a.port(), Channel.RELEASE, tagOrMain(a.image()), name);
            }
            case DEV -> {
                DevDeployment d = devApps.findByZoneAndName(slug, name)
                        .orElseThrow(() -> new IllegalArgumentException(name + " has no dev deployment"));
                deploy(slug, name, d.image(), d.port(), Channel.DEV, "main", name);
            }
            case PREVIEW -> {
                List<DeployedApp> previews = previewsForApp(slug, name);
                if (previews.isEmpty()) {
                    throw new IllegalArgumentException(name + " has no PR previews deployed");
                }
                for (DeployedApp p : previews) {
                    redeployPreview(slug, name, p.name());
                }
            }
        }
    }

    /**
     * Redeploy one PR preview ({@code <repo>-pr-<n>}) with the image it already
     * runs, reading its {@code compose.yaml}/{@code .env} at the PR's current head
     * commit — so a changed secret or a changed compose file takes effect without
     * pushing to the PR.
     */
    public void redeployPreview(String slug, String repo, String previewName) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^" + java.util.regex.Pattern.quote(repo) + "-pr-(\\d+)$").matcher(previewName);
        if (!m.matches()) {
            throw new IllegalArgumentException(previewName + " is not a PR preview of " + repo);
        }
        DeployedApp a = apps.findByZoneAndName(slug, previewName)
                .orElseThrow(() -> new IllegalArgumentException("no such preview: " + previewName));
        int number = Integer.parseInt(m.group(1));
        String sha = zoneService.pullHeadSha(slug, repo, number)
                .orElseThrow(() -> new IllegalArgumentException("couldn't read PR #" + number + " from Forgejo"));
        deploy(slug, previewName, a.image(), a.port(), Channel.RELEASE, sha, repo);
    }

    /** {@code vX.Y.Z} if the image is tagged with one (the ref its compose.yaml was read at), else {@code main}. */
    private static String tagOrMain(String image) {
        int i = image == null ? -1 : image.lastIndexOf(':');
        String tag = i < 0 ? "" : image.substring(i + 1);
        return tag.matches("v\\d+\\.\\d+\\.\\d+") ? tag : "main";
    }

    /** Like {@link #stop}, but recorded as the idle sweep's doing, so a browser visit may wake it. */
    public void stopIdle(String slug, String name) {
        DeployedApp app = apps.findByZoneAndName(slug, name)
                .orElseThrow(() -> new IllegalArgumentException("zone " + slug + " has no app '" + name + "'"));
        lifecycle(slug, name, Channel.RELEASE, "stop");
        app.markIdleStopped();
        apps.save(app);
    }

    /** Dev counterpart of {@link #stopIdle}. */
    public void stopDevIdle(String slug, String name) {
        DevDeployment dev = devApps.findByZoneAndName(slug, name)
                .orElseThrow(() -> new IllegalArgumentException("zone " + slug + " has no dev deployment of '" + name + "'"));
        lifecycle(slug, name, Channel.DEV, "stop");
        dev.markIdleStopped();
        devApps.save(dev);
    }

    /** Stop a dev deployment's containers. Idempotent. */
    public void stopDev(String slug, String name) {
        DevDeployment dev = devApps.findByZoneAndName(slug, name)
                .orElseThrow(() -> new IllegalArgumentException("zone " + slug + " has no dev deployment of '" + name + "'"));
        lifecycle(slug, name, Channel.DEV, "stop");
        dev.markStopped();
        devApps.save(dev);
    }

    /** Restart a stopped dev deployment. Idempotent. */
    public void startDev(String slug, String name) {
        DevDeployment dev = devApps.findByZoneAndName(slug, name)
                .orElseThrow(() -> new IllegalArgumentException("zone " + slug + " has no dev deployment of '" + name + "'"));
        lifecycle(slug, name, Channel.DEV, "start");
        dev.markRunning();
        devApps.save(dev);
    }

    /**
     * Stop or (re)start an app's containers without a redeploy. {@code compose
     * stop}/{@code start} (unlike {@code down}) can't reconstruct a project from
     * container labels alone, so the last deploy's rendered compose file is
     * passed — it pins the exact images, so even if the containers were removed
     * (node reboot, prune) a {@code start} can recreate them with {@code up -d}.
     * Cases:
     * <ul>
     *   <li>compose file on disk, containers exist → {@code compose -f … stop|start}</li>
     *   <li>compose file on disk, {@code start} but containers gone → {@code compose -f … up -d}</li>
     *   <li>no compose file → label-filtered {@code docker stop|start}; a
     *       {@code start} with nothing to act on is unrecoverable here (cut a release)</li>
     * </ul>
     */
    private void lifecycle(String slug, String name, Channel channel, String verb) {
        Zone z = zones.find(slug).orElse(null);
        String host = dockerHost(z);
        String project = "app-" + slug + "-" + name + channel.projectSuffix();
        Path composeFile = render.appComposePath(slug, name, channel);
        boolean start = verb.equals("start");

        DockerCli.Result r;
        if (java.nio.file.Files.exists(composeFile)) {
            String cf = composeFile.toString();
            if (start && containerIds(host, project, true).isEmpty()) {
                if (z != null) {
                    registryLogin(z);
                }
                r = docker.composeUp(host, project, cf);
            } else if (start && !containerIds(host, project, false).isEmpty()) {
                // something is still running, so the console is offering Start because
                // it is unhealthy or not answering its health check: `start` alone would
                // be a no-op on running containers. Revive any stopped ones, then bounce.
                r = docker.compose(host, project, cf, "start");
                if (r.ok()) {
                    r = docker.compose(host, project, cf, "restart");
                }
            } else {
                r = docker.compose(host, project, cf, verb);
            }
        } else {
            List<String> ids = containerIds(host, project, start);
            if (ids.isEmpty()) {
                if (start) {
                    throw new DeployException("nothing to start — the containers and the rendered "
                            + "compose are both gone. Cut a release (or Deploy from main) to redeploy.");
                }
                r = new DockerCli.Result(0, "", "");
            } else {
                List<String> args = new java.util.ArrayList<>(List.of(verb));
                args.addAll(ids);
                r = docker.docker(host, args);
            }
        }
        if (!r.ok()) {
            throw new DeployException("compose " + verb + " failed: " + firstLine(r.stderr()));
        }
        if (z != null) {
            touch(z);
        }
    }

    /**
     * Whether each of the zone's release apps is actually serving, as opposed to
     * what the {@code running} flag says: that flag only moves on Start/Stop, so
     * a container that was removed (prune, a down, a crash) or is failing still
     * read "running", and the console offered Stop for an app whose URL was
     * returning 404. An app is live when its containers are up and none is
     * unhealthy, <em>and</em> its URL answers its health check
     * ({@link AppHealthProbe}). Falls back to the flag if the node can't be asked.
     */
    public Map<String, Boolean> liveStates(String slug) {
        Zone z = zones.find(slug).orElse(null);
        Map<String, Proj> snap = projectHealth(dockerHost(z));
        List<DeployedApp> list = apps.findByZone(slug);
        Map<String, Boolean> out = new HashMap<>();
        if (snap == null) {
            list.forEach(a -> out.put(a.name(), a.running()));
            return out;
        }
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, CompletableFuture<Boolean>> probes = new HashMap<>();
            for (DeployedApp a : list) {
                Proj p = snap.get("app-" + slug + "-" + a.name());
                if (p == null || !p.up()) {
                    out.put(a.name(), false);
                } else if (z == null) {
                    out.put(a.name(), true);
                } else {
                    probes.put(a.name(), CompletableFuture.supplyAsync(
                            () -> health.up(a.url(z.baseDomain()), p.healthPath()), pool));
                }
            }
            probes.forEach((name, f) -> out.put(name, f.join()));
        }
        return out;
    }

    /** {@link #liveStates} for every dev deployment in the zone, keyed by app name. */
    public Map<String, Boolean> devLiveStates(String slug) {
        Zone z = zones.find(slug).orElse(null);
        List<DevDeployment> list = devApps.findByZoneOrderByName(slug);
        Map<String, Boolean> out = new HashMap<>();
        if (list.isEmpty()) {
            return out;
        }
        Map<String, Proj> snap = projectHealth(dockerHost(z));
        if (snap == null) {
            list.forEach(d -> out.put(d.name(), d.running()));
            return out;
        }
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, CompletableFuture<Boolean>> probes = new HashMap<>();
            for (DevDeployment d : list) {
                Proj p = snap.get("app-" + slug + "-" + d.name() + "-dev");
                if (p == null || !p.up()) {
                    out.put(d.name(), false);
                } else if (z == null) {
                    out.put(d.name(), true);
                } else {
                    probes.put(d.name(), CompletableFuture.supplyAsync(
                            () -> health.up(d.url(z.baseDomain()), p.healthPath()), pool));
                }
            }
            probes.forEach((name, f) -> out.put(name, f.join()));
        }
        return out;
    }

    /** {@link #liveStates} for a dev deployment; falls back to its flag. */
    public boolean devLive(String slug, String name, boolean flag) {
        Zone z = zones.find(slug).orElse(null);
        Map<String, Proj> snap = projectHealth(dockerHost(z));
        if (snap == null) {
            return flag;
        }
        Proj p = snap.get("app-" + slug + "-" + name + "-dev");
        if (p == null || !p.up()) {
            return false;
        }
        return z == null || devApps.findByZoneAndName(slug, name)
                .map(d -> health.up(d.url(z.baseDomain()), p.healthPath())).orElse(true);
    }

    /**
     * One {@code docker ps -a} per node, grouped by compose project: a project
     * is up when it has containers, every one is {@code running}, and none
     * reports {@code (unhealthy)}; it also carries the web service's
     * {@code ignition.health.path} label. A project with no containers is simply
     * absent (gone). {@code null} if the node can't be queried.
     */
    private Map<String, Proj> projectHealth(String host) {
        Snapshot cached = snapshots.get(host);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < SNAPSHOT_TTL_MS) {
            return cached.byProject();
        }
        DockerCli.Result ps = docker.docker(host, List.of("ps", "-a",
                "--filter", "label=com.docker.compose.project",
                "--format", "{{.Label \"com.docker.compose.project\"}}\t{{.State}}\t{{.Status}}"
                        + "\t{{.Label \"" + AppHealthProbe.LABEL + "\"}}"));
        if (!ps.ok()) {
            log.warn("container snapshot on {} failed: {}", host, firstLine(ps.stderr()));
            return cached == null ? null : cached.byProject();
        }
        Map<String, Proj> byProject = new HashMap<>();
        for (String line : ps.stdout().split("\n")) {
            String[] f = line.split("\t", 4);
            if (f.length < 3 || !f[0].startsWith("app-")) {
                continue;
            }
            boolean up = f[1].strip().equals("running") && !f[2].contains("unhealthy");
            String path = f.length > 3 ? f[3].strip() : "";
            byProject.merge(f[0].strip(), new Proj(up, path),
                    (x, y) -> new Proj(x.up() && y.up(), x.healthPath().isBlank() ? y.healthPath() : x.healthPath()));
        }
        snapshots.put(host, new Snapshot(now, byProject));
        return byProject;
    }

    private List<String> containerIds(String host, String project, boolean includeStopped) {
        DockerCli.Result ps = docker.docker(host, List.of("ps",
                includeStopped ? "-aq" : "-q",
                "--filter", "label=com.docker.compose.project=" + project));
        return ps.stdout().lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    public java.util.Optional<DevDeployment> devDeployment(String slug, String name) {
        return devApps.findByZoneAndName(slug, name);
    }

    /** Live PR previews of {@code repo} in this zone — the {@code <repo>-pr-<n>} deployments. */
    public List<DeployedApp> previewsForApp(String slug, String repo) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "^" + java.util.regex.Pattern.quote(repo) + "-pr-\\d+$");
        return apps.findByZone(slug).stream().filter(a -> p.matcher(a.name()).matches()).toList();
    }

    public List<DevDeployment> devDeploymentsForZone(String slug) {
        return devApps.findByZoneOrderByName(slug);
    }

    // ---------------------------------------------------------------------------

    private String dockerHost(Zone z) {
        String node = z == null ? "" : z.node();
        return nodes.findById(node).map(n -> n.dockerHost()).orElse("local");
    }

    /**
     * {@code docker login} the zone's Forgejo registry on the app's node before
     * the compose {@code --pull} — app images are private packages, so the
     * node needs credentials to pull them (and the per-node Watchtower reads
     * the same {@code ~/.docker/config.json} it writes). Uses the zone bot's
     * token. Best-effort: a warning, not a hard failure — a public package or
     * an already-logged-in node still deploys, and the compose error is
     * clearer than a login error if it really is missing.
     */
    private boolean registryLogin(Zone z) {
        String registry = z.gitHost();
        String token = zones.secret(z.slug(), "forgejo_token");
        if (token.isBlank()) {
            log.warn("deploy {}: no forgejo_token secret — not logging the node in to {}; "
                    + "a private image pull will fail", z.slug(), registry);
            return false;
        }
        DockerCli.Result r = docker.docker(dockerHost(z),
                List.of("login", registry, "-u", ProvisioningService.BOT_USER, "--password-stdin"),
                token);
        if (r.ok()) {
            log.info("deploy {}: node logged in to registry {}", z.slug(), registry);
            loggedIn.add(z.slug());
            return true;
        }
        log.warn("deploy {}: docker login {} on node failed: {}",
                z.slug(), registry, firstLine(r.stderr()));
        return false;
    }

    /**
     * Log in every zone whose registry login hasn't succeeded yet — a few
     * seconds after startup, then every {@code ignition.registry-login.interval}
     * (default 5m) until it has. The credentials live in the shared
     * {@code ignition-docker-config} volume that Watchtower reads, so a fresh
     * volume, or a registry/edge that wasn't up yet at startup (a 502 right
     * after Traefik restarts), would otherwise leave Watchtower unable to pull
     * until each zone's next deploy. Once every zone is logged in this does
     * nothing. Off the scheduler thread so a registry that hangs can't hold up
     * the idle sweeper.
     */
    @Scheduled(initialDelayString = "${ignition.registry-login.initial-delay:15s}",
            fixedDelayString = "${ignition.registry-login.interval:5m}")
    public void loginPendingZones() {
        if (!loginRunning.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("registry-login").start(() -> {
            try {
                for (Zone z : zones.findAll()) {
                    if (loggedIn.contains(z.slug())) {
                        continue;
                    }
                    try {
                        registryLogin(z);
                    } catch (RuntimeException e) {
                        log.warn("registry login for {} failed: {}", z.slug(), e.getMessage());
                    }
                }
            } finally {
                loginRunning.set(false);
            }
        });
    }

    private void touch(Zone z) {
        z.touch();
        zones.save(z);
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("").strip();
    }

    /** Compose failed while applying the deploy — maps to HTTP 502. */
    public static class DeployException extends RuntimeException {
        public DeployException(String message) {
            super(message);
        }
    }
}
