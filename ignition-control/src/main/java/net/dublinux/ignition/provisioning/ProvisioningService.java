package net.dublinux.ignition.provisioning;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import net.dublinux.ignition.config.IgnitionProperties;
import net.dublinux.ignition.config.IgnitionProperties.Quotas;
import net.dublinux.ignition.docker.DockerCli;
import net.dublinux.ignition.node.Node;
import net.dublinux.ignition.node.NodeRepository;
import net.dublinux.ignition.scheduler.Scheduler;
import net.dublinux.ignition.state.EnvFile;
import net.dublinux.ignition.templates.ComposeTemplate;
import net.dublinux.ignition.traefik.TraefikDynamicConfig;
import net.dublinux.ignition.zone.Zone;
import net.dublinux.ignition.zone.ZoneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stands up one zone's stack on a node — Forgejo + private DinD + Actions
 * runner, a zone-admin account, and the tokens the control plane and CI need.
 * A faithful port of {@code provision-zone.sh}: the two-phase apply (bring up
 * forgejo+dind, wait healthy, register the runner secret, push the config in
 * with {@code compose cp}, bring up the runner). Idempotent.
 *
 * <p>Runs off-request on a single-thread executor; the console polls
 * {@link #status(String)}.
 */
@Service
public class ProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningService.class);
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final IgnitionProperties props;
    private final ZoneRepository zones;
    private final NodeRepository nodes;
    private final Scheduler scheduler;
    private final ComposeTemplate templates;
    private final TraefikDynamicConfig traefik;
    private final DockerCli docker;

    private final ExecutorService pool;
    private final Map<String, Status> statuses = new ConcurrentHashMap<>();

    public ProvisioningService(IgnitionProperties props, ZoneRepository zones, NodeRepository nodes,
                               Scheduler scheduler, ComposeTemplate templates,
                               TraefikDynamicConfig traefik, DockerCli docker,
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
        this.templates = templates;
        this.traefik = traefik;
        this.docker = docker;
    }

    public enum State { RUNNING, DONE, FAILED }

    public record Status(State state, String message, Instant at) {}

    public Optional<Status> status(String slug) {
        return Optional.ofNullable(statuses.get(slug));
    }

    /** Queue a provision. Returns immediately; watch {@link #status(String)}. */
    public void submit(String slug, String nodeOverride, String label) {
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("slug must be [a-z0-9-], 2–40 chars, no leading/trailing dash");
        }
        Status existing = statuses.get(slug);
        if (existing != null && existing.state() == State.RUNNING) {
            throw new IllegalStateException("zone " + slug + " is already being provisioned");
        }
        statuses.put(slug, new Status(State.RUNNING, "queued", Instant.now()));
        pool.submit(() -> {
            try {
                String node = provision(slug, nodeOverride, label);
                statuses.put(slug, new Status(State.DONE, "provisioned on " + node, Instant.now()));
            } catch (RuntimeException e) {
                log.warn("provisioning zone {} failed", slug, e);
                statuses.put(slug, new Status(State.FAILED, e.getMessage(), Instant.now()));
            }
        });
    }

    // ---------------------------------------------------------------- the flow

    String provision(String slug, String nodeOverride, String label) {
        Quotas q = props.getQuotas();
        double zoneCpus = q.getCpuForgejo() + q.getCpuDind() + q.getCpuRunner() + q.getCpuApp();
        double zoneMemGb = gb(q.getMemForgejo()) + gb(q.getMemDind()) + gb(q.getMemRunner()) + gb(q.getMemApp());

        String node = pickNode(slug, nodeOverride, label, zoneCpus, zoneMemGb);
        String dockerHost = nodes.find(node).map(Node::dockerHost).orElse("local");

        String base = props.getBaseDomain();
        String gitHost = "git." + slug + "." + base;
        String zadminHost = "admin." + slug + "." + base;

        Path dir = zones.dir(slug);
        mkdirs(dir);
        writeZoneEnv(slug, node, base, zoneCpus, zoneMemGb, gitHost, zadminHost);

        traefik.writePlatformRouter();
        traefik.writeZoneRouter(slug);

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("ZONE_SLUG", slug);
        vars.put("BASE_DOMAIN", base);
        vars.put("CPU_FORGEJO", num(q.getCpuForgejo()));
        vars.put("MEM_FORGEJO", q.getMemForgejo());
        vars.put("CPU_DIND", num(q.getCpuDind()));
        vars.put("MEM_DIND", q.getMemDind());
        vars.put("CPU_RUNNER", num(q.getCpuRunner()));
        vars.put("MEM_RUNNER", q.getMemRunner());
        writeString(dir.resolve("docker-compose.yml"), templates.renderZone(vars));

        // --- phase 1: forgejo + dind, wait healthy ---
        must(zc(slug, dockerHost, "up", "-d", "forgejo", "dind"), "compose up forgejo+dind");
        awaitForgejoHealthy(slug, dockerHost);

        // --- runner registration (two-phase) ---
        String secret = secretFile(dir.resolve("runner-secret"), 20);
        String uuid = forgejoUuid(secret);
        must(forgejoCli(slug, dockerHost,
                "forgejo", "forgejo-cli", "actions", "register",
                "--keep-labels", "--name", "zone-" + slug, "--secret", secret),
                "register runner secret");
        writeString(dir.resolve("runner-config.yml"), runnerConfig(q.getRunnerCapacity(), uuid, secret));

        // --- phase 2: runner up, push its config into the volume ---
        must(zc(slug, dockerHost, "up", "-d"), "compose up (runner)");
        must(zc(slug, dockerHost, "cp", dir.resolve("runner-config.yml").toString(), "runner:/data/config.yml"),
                "compose cp runner config");
        must(zc(slug, dockerHost, "restart", "runner"), "restart runner");

        // --- zone-admin account + Forgejo API token ---
        if (!Files.isRegularFile(dir.resolve("zone-admin.txt"))) {
            String adminPw = randBase64(18);
            must(forgejoCli(slug, dockerHost,
                    "forgejo", "admin", "user", "create", "--admin", "--username", "zoneadmin",
                    "--password", adminPw, "--email", "zoneadmin@" + gitHost, "--must-change-password=false"),
                    "create zoneadmin");
            DockerCli.Result tok = forgejoCli(slug, dockerHost,
                    "forgejo", "admin", "user", "generate-access-token",
                    "--username", "zoneadmin", "--scopes", "all", "--raw");
            must(tok, "mint zoneadmin token");
            Map<String, String> adminFile = new LinkedHashMap<>();
            adminFile.put("username", "zoneadmin");
            adminFile.put("password", adminPw);
            adminFile.put("forgejo_url", "https://" + gitHost + "/");
            adminFile.put("forgejo_token", tok.stdout().replaceAll("\\s", ""));
            EnvFile.write(dir.resolve("zone-admin.txt"), adminFile);
        }

        // --- tokens + activity marker ---
        secretFileIfMissing(dir.resolve("zone-token"), 32);
        secretFileIfMissing(dir.resolve("deploy-token"), 32);
        writeString(dir.resolve("last-activity"), Long.toString(Instant.now().getEpochSecond()));

        log.info("provisioned zone {} on node {} ({})", slug, node, gitHost);
        return node;
    }

    // ---------------------------------------------------------------- helpers

    private String pickNode(String slug, String override, String label, double cpu, double mem) {
        if (zones.exists(slug) && (override == null || override.isBlank())) {
            return zones.find(slug).map(Zone::node).orElseThrow();
        }
        if (override != null && !override.isBlank()) {
            if (nodes.find(override).isEmpty()) {
                throw new IllegalArgumentException("no such node: " + override);
            }
            return override;
        }
        return scheduler.place(cpu, mem, label);
    }

    private void writeZoneEnv(String slug, String node, String base, double cpus, double memGb,
                              String gitHost, String zadminHost) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("ZONE_SLUG", slug);
        env.put("NODE", node);
        env.put("BASE_DOMAIN", base);
        env.put("ZONE_CPUS", num(cpus));
        env.put("ZONE_MEM_GB", num(memGb));
        env.put("APP_PORT", "8080");
        env.put("GIT_HOST", gitHost);
        env.put("ZADMIN_HOST", zadminHost);
        env.put("REGISTRY", gitHost);
        env.put("FORGEJO_URL", "https://" + gitHost + "/");
        env.put("ZADMIN_URL", "https://" + zadminHost + "/");
        env.put("APPS_BASE", "apps." + slug + "." + base);
        zones.saveEnv(slug, env);
    }

    private DockerCli.Result zc(String slug, String dockerHost, String... args) {
        String composeFile = zones.dir(slug).resolve("docker-compose.yml").toString();
        return docker.compose(dockerHost, "zone-" + slug, composeFile, args);
    }

    /** {@code docker … compose exec -T -u git forgejo <cmd…>} */
    private DockerCli.Result forgejoCli(String slug, String dockerHost, String... cmd) {
        String[] a = new String[cmd.length + 5];
        a[0] = "exec"; a[1] = "-T"; a[2] = "-u"; a[3] = "git"; a[4] = "forgejo";
        System.arraycopy(cmd, 0, a, 5, cmd.length);
        return zc(slug, dockerHost, a);
    }

    private void awaitForgejoHealthy(String slug, String dockerHost) {
        for (int i = 0; i < 60; i++) {
            DockerCli.Result r = zc(slug, dockerHost, "ps", "--format", "{{.Service}} {{.Health}}");
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

    private static String runnerConfig(int capacity, String uuid, String secret) {
        return """
                log: { level: info }
                runner:
                  file: /data/.runner
                  capacity: %d
                  timeout: 3h
                  envs:
                    DOCKER_HOST: tcp://dind:2375
                  labels:
                    - "ubuntu-latest:docker://code.forgejo.org/oci/node:22-bookworm"
                    - "docker-cli:docker://code.forgejo.org/oci/docker:cli"
                container: { network: host, valid_volumes: [] }
                server:
                  connections:
                    default: { url: "http://forgejo:3000", uuid: "%s", token: "%s" }
                """.formatted(capacity, uuid, secret);
    }

    private String secretFile(Path p, int bytes) {
        if (isNonEmpty(p)) {
            return read(p).strip();
        }
        String hex = randHex(bytes);
        writeString(p, hex + "\n");
        return hex;
    }

    private void secretFileIfMissing(Path p, int bytes) {
        if (!isNonEmpty(p)) {
            writeString(p, randHex(bytes) + "\n");
        }
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

    private static String num(double d) {
        return d == Math.floor(d) ? "%.1f".formatted(d) : Double.toString(d);
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

    private static boolean isNonEmpty(Path p) {
        try {
            return Files.isRegularFile(p) && Files.size(p) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static void mkdirs(Path p) {
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            throw new UncheckedIOException("mkdir " + p, e);
        }
    }

    private static void writeString(Path p, String content) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, content);
        } catch (IOException e) {
            throw new UncheckedIOException("writing " + p, e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + p, e);
        }
    }
}
