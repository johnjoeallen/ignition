package net.dublinux.ignition.provisioning;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import java.util.UUID;

import net.dublinux.ignition.auth.ZoneMember;
import net.dublinux.ignition.auth.ZoneMemberRepository;
import net.dublinux.ignition.config.IgnitionProperties;
import net.dublinux.ignition.config.IgnitionProperties.Quotas;
import net.dublinux.ignition.docker.DockerCli;
import net.dublinux.ignition.node.Node;
import net.dublinux.ignition.node.NodeRepository;
import net.dublinux.ignition.scheduler.Scheduler;
import net.dublinux.ignition.templates.RenderService;
import net.dublinux.ignition.traefik.TraefikDynamicConfig;
import net.dublinux.ignition.zone.Zone;
import net.dublinux.ignition.zone.ZoneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stands up one zone's stack on a node — Forgejo + private DinD + Actions
 * runner, a zone-admin account, and the tokens the control plane and CI need.
 * The two-phase apply (bring up forgejo+dind, wait healthy, register the runner
 * secret, push the config in with {@code compose cp}, bring up the runner).
 * Idempotent.
 *
 * <p>Runs off-request on a small executor; the console polls
 * {@link #status(String)}.
 */
@Service
public class ProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningService.class);
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$");
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The Forgejo service account ignition-control acts as for every zone-admin
     * action. Not a login for humans — its password is random and never shown.
     * (Was {@code zoneadmin}; renamed to stop it being confused with the
     * Ignition "zone admin" role, which is a different thing entirely.)
     */
    public static final String BOT_USER = "ignition-bot";

    private final IgnitionProperties props;
    private final ZoneRepository zones;
    private final NodeRepository nodes;
    private final Scheduler scheduler;
    private final RenderService render;
    private final TraefikDynamicConfig traefik;
    private final DockerCli docker;
    private final ProvisioningStatusRepository statuses;
    private final ZoneMemberRepository zoneMembers;

    private final ExecutorService pool;

    public ProvisioningService(IgnitionProperties props, ZoneRepository zones, NodeRepository nodes,
                               Scheduler scheduler, RenderService render,
                               TraefikDynamicConfig traefik, DockerCli docker,
                               ProvisioningStatusRepository statuses, ZoneMemberRepository zoneMembers,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${ignition.provisioning.concurrency:3}") int concurrency) {
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        this.pool = Executors.newFixedThreadPool(Math.max(1, concurrency), r -> {
            Thread t = new Thread(r, "provisioner-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.props = props;
        this.zones = zones;
        this.nodes = nodes;
        this.scheduler = scheduler;
        this.render = render;
        this.traefik = traefik;
        this.docker = docker;
        this.statuses = statuses;
        this.zoneMembers = zoneMembers;
    }

    public enum State { RUNNING, DONE, FAILED }

    public record Status(State state, String message, Instant at) {}

    public Optional<Status> status(String slug) {
        return statuses.findById(slug)
                .map(e -> new Status(e.state(), e.message(), e.updatedAt()));
    }

    private void setStatus(String slug, State state, String message) {
        ProvisioningStatusEntity e = statuses.findById(slug)
                .orElseGet(() -> new ProvisioningStatusEntity(slug, state, message));
        e.set(state, message);
        statuses.save(e);
    }

    /**
     * Queue a provision. Returns immediately; watch {@link #status(String)}.
     * {@code creator} becomes the zone's {@code ZONE_ADMIN} the moment it's
     * created (a platform admin, so also gets in via {@code PLATFORM_ADMIN} —
     * this just means they show up in the team's own member list too, and can
     * hand admin off). {@code null} on a re-provision (move) of an existing
     * zone — membership is untouched then.
     */
    public void submit(String slug, String nodeOverride, String label, UUID creator) {
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("slug must be [a-z0-9-], 2–40 chars, no leading/trailing dash");
        }
        status(slug).ifPresent(s -> {
            if (s.state() == State.RUNNING) {
                throw new IllegalStateException("zone " + slug + " is already being provisioned");
            }
        });
        setStatus(slug, State.RUNNING, "queued");
        pool.submit(() -> {
            try {
                String node = provision(slug, nodeOverride, label, creator);
                setStatus(slug, State.DONE, "provisioned on " + node);
            } catch (RuntimeException e) {
                log.warn("provisioning zone {} failed", slug, e);
                setStatus(slug, State.FAILED, e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- the flow

    String provision(String slug, String nodeOverride, String label, UUID creator) {
        Quotas q = props.getQuotas();
        double zoneCpus = q.getCpuForgejo() + q.getCpuDind() + q.getCpuRunner() + q.getCpuApp();
        double zoneMemGb = gb(q.getMemForgejo()) + gb(q.getMemDind()) + gb(q.getMemRunner()) + gb(q.getMemApp());

        String node = pickNode(slug, nodeOverride, label, zoneCpus, zoneMemGb);
        String dockerHost = nodes.findById(node).map(Node::dockerHost).orElse("local");

        String base = props.getBaseDomain();
        String gitHost = "git." + slug + "." + base;

        Zone zone = zones.find(slug).orElse(null);
        boolean isNew = zone == null;
        if (isNew) {
            zone = new Zone(slug, node, base, zoneCpus, zoneMemGb, gitHost,
                    "https://" + gitHost + "/", "apps." + slug + "." + base);
            zone.setCreatedBy(creator);
        } else {
            zone.setNode(node);
        }
        zone.touch();
        zone = zones.save(zone);
        if (isNew && creator != null) {
            zoneMembers.save(new ZoneMember(slug, creator, ZoneMember.Role.ZONE_ADMIN));
        }

        traefik.writePlatformRouter();

        Path compose = render.zoneCompose(zone);

        // --- phase 1: forgejo + dind, wait healthy ---
        must(zc(slug, dockerHost, compose, "up", "-d", "forgejo", "dind"), "compose up forgejo+dind");
        awaitForgejoHealthy(slug, dockerHost, compose);

        // --- runner registration (two-phase) ---
        String secret = zones.hasSecret(slug, "runner-secret")
                ? zones.secret(slug, "runner-secret")
                : putRandomHex(slug, "runner-secret", 20);
        String uuid = forgejoUuid(secret);
        must(forgejoCli(slug, dockerHost, compose,
                "forgejo", "forgejo-cli", "actions", "register",
                "--keep-labels", "--name", "zone-" + slug, "--secret", secret),
                "register runner secret");
        Path runnerConfig = render.runnerConfig(slug, q.getRunnerCapacity(), uuid, secret);

        // --- phase 2: runner up, push its config into the volume ---
        must(zc(slug, dockerHost, compose, "up", "-d"), "compose up (runner)");
        must(zc(slug, dockerHost, compose, "cp", runnerConfig.toString(), "runner:/data/config.yml"),
                "compose cp runner config");
        must(zc(slug, dockerHost, compose, "restart", "runner"), "restart runner");

        // --- Forgejo API service account + token (drives every zone-admin
        // action on the caller's behalf; nobody signs in as it — its password
        // is random and never shown) ---
        if (!zones.hasSecret(slug, "forgejo_token")) {
            String adminPw = randBase64(18);
            must(forgejoCli(slug, dockerHost, compose,
                    "forgejo", "admin", "user", "create", "--admin", "--username", BOT_USER,
                    "--password", adminPw, "--email", BOT_USER + "@" + gitHost, "--must-change-password=false"),
                    "create " + BOT_USER);
            DockerCli.Result tok = forgejoCli(slug, dockerHost, compose,
                    "forgejo", "admin", "user", "generate-access-token",
                    "--username", BOT_USER, "--scopes", "all", "--raw");
            must(tok, "mint " + BOT_USER + " token");
            zones.putSecret(slug, "forgejo_username", BOT_USER);
            zones.putSecret(slug, "forgejo_password", adminPw);
            zones.putSecret(slug, "forgejo_url", "https://" + gitHost + "/");
            zones.putSecret(slug, "forgejo_token", tok.stdout().replaceAll("\\s", ""));
        }

        // --- tokens ---
        if (!zones.hasSecret(slug, "zone-token")) {
            putRandomHex(slug, "zone-token", 32);
        }
        if (!zones.hasSecret(slug, "deploy-token")) {
            putRandomHex(slug, "deploy-token", 32);
        }

        zone.touch();
        zones.save(zone);

        log.info("provisioned zone {} on node {} ({})", slug, node, gitHost);
        return node;
    }

    // ---------------------------------------------------------------- helpers

    private String pickNode(String slug, String override, String label, double cpu, double mem) {
        if (zones.exists(slug) && (override == null || override.isBlank())) {
            return zones.find(slug).map(Zone::node).orElseThrow();
        }
        if (override != null && !override.isBlank()) {
            if (nodes.findById(override).isEmpty()) {
                throw new IllegalArgumentException("no such node: " + override);
            }
            return override;
        }
        return scheduler.place(cpu, mem, label);
    }

    private DockerCli.Result zc(String slug, String dockerHost, Path compose, String... args) {
        return docker.compose(dockerHost, "zone-" + slug, compose.toString(), args);
    }

    /** {@code docker … compose exec -T -u git forgejo <cmd…>} */
    private DockerCli.Result forgejoCli(String slug, String dockerHost, Path compose, String... cmd) {
        String[] a = new String[cmd.length + 5];
        a[0] = "exec"; a[1] = "-T"; a[2] = "-u"; a[3] = "git"; a[4] = "forgejo";
        System.arraycopy(cmd, 0, a, 5, cmd.length);
        return zc(slug, dockerHost, compose, a);
    }

    private void awaitForgejoHealthy(String slug, String dockerHost, Path compose) {
        for (int i = 0; i < 60; i++) {
            DockerCli.Result r = zc(slug, dockerHost, compose, "ps", "--format", "{{.Service}} {{.Health}}");
            for (String line : r.stdout().split("\n")) {
                String[] parts = line.strip().split("\\s+");
                if (parts.length >= 2 && parts[0].equals("forgejo") && parts[1].equals("healthy")) {
                    return;
                }
            }
            sleep(2000);
        }
        throw new IllegalStateException("forgejo did not become healthy");
    }

    private String putRandomHex(String slug, String name, int bytes) {
        String hex = randHex(bytes);
        zones.putSecret(slug, name, hex);
        return hex;
    }

    private static void must(DockerCli.Result r, String what) {
        if (!r.ok()) {
            throw new IllegalStateException(what + " failed: "
                    + firstLine(r.stderr().isBlank() ? r.stdout() : r.stderr()));
        }
    }

    static String forgejoUuid(String secret) {
        byte[] ascii = secret.substring(0, 16).getBytes(StandardCharsets.US_ASCII);
        String h = HexFormat.of().formatHex(ascii); // 32 hex chars
        return "%s-%s-%s-%s-%s".formatted(
                h.substring(0, 8), h.substring(8, 12), h.substring(12, 16),
                h.substring(16, 20), h.substring(20, 32));
    }

    private static String randHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String randBase64(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return java.util.Base64.getEncoder().encodeToString(b);
    }

    private static double gb(String mem) {
        String s = mem.strip().toLowerCase();
        if (s.endsWith("g")) {
            s = s.substring(0, s.length() - 1);
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("").strip();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
