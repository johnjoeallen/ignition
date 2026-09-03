package net.dublinux.ignition.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import net.dublinux.ignition.config.IgnitionProperties;
import net.dublinux.ignition.docker.DockerCli;
import net.dublinux.ignition.node.NodeRepository;
import net.dublinux.ignition.state.EnvFile;
import net.dublinux.ignition.templates.ComposeTemplate;
import net.dublinux.ignition.zone.Zone;
import net.dublinux.ignition.zone.ZoneRepository;
import org.springframework.stereotype.Service;

/**
 * Deployed-app views and the CI bridge. Mirrors {@code deploy()} /
 * {@code undeploy()} in {@code ign-control.py}: the app is rendered from
 * {@code app-compose.tmpl} and applied on the zone's node (not inside its DinD
 * sandbox), as compose project {@code app-<slug>-<name>}.
 */
@Service
public class AppService {

    private static final Pattern NAME = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$");
    private static final DateTimeFormatter DEPLOY_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final AppRepository apps;
    private final ZoneRepository zones;
    private final NodeRepository nodes;
    private final DockerCli docker;
    private final ComposeTemplate templates;
    private final IgnitionProperties props;

    public AppService(AppRepository apps, ZoneRepository zones, NodeRepository nodes,
                      DockerCli docker, ComposeTemplate templates, IgnitionProperties props) {
        this.apps = apps;
        this.zones = zones;
        this.nodes = nodes;
        this.docker = docker;
        this.templates = templates;
        this.props = props;
    }

    public List<DeployedApp> list() {
        return apps.findAll();
    }

    public List<DeployedApp> listForZone(String slug) {
        return apps.findByZone(slug);
    }

    public record DeployResult(String zone, String app, String deployId, String url) {}

    /** {@code POST /deploy} — bearer already resolved to {@code slug}. */
    public DeployResult deploy(String slug, String name, String image, int port) {
        Zone z = zones.find(slug)
                .orElseThrow(() -> new IllegalArgumentException("no such zone: " + slug));
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "app name must be [a-z0-9-], 1–40 chars, no leading/trailing dash");
        }
        Map<String, String> zoneEnv = EnvFile.read(zones.dir(slug).resolve("zone.env"));
        String registry = zoneEnv.getOrDefault("REGISTRY",
                "git." + slug + "." + z.baseDomain());
        if (!image.startsWith(registry + "/")) {
            throw new IllegalArgumentException("image must be from " + registry + "/");
        }

        String deployId = DEPLOY_ID.format(Instant.now());
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("APP_NAME", name);
        vars.put("ZONE_SLUG", slug);
        vars.put("BASE_DOMAIN", z.baseDomain());
        vars.put("APP_IMAGE", image);
        vars.put("APP_PORT", Integer.toString(port));
        vars.put("DEPLOY_ID", deployId);
        vars.put("CPU_APP", Double.toString(props.getQuotas().getCpuApp()));
        vars.put("MEM_APP", props.getQuotas().getMemApp());

        Path dir = apps.dir(slug);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("mkdir " + dir, e);
        }
        Path rendered = dir.resolve(name + "-compose.yml");
        try {
            Files.writeString(rendered, templates.renderApp(vars));
        } catch (IOException e) {
            throw new UncheckedIOException("writing " + rendered, e);
        }

        DockerCli.Result r = compose(slug, name, rendered,
                "up", "-d", "--pull", "always", "--remove-orphans");
        if (!r.ok()) {
            throw new DeployException("compose up failed: " + firstLine(r.stderr()));
        }

        Map<String, String> record = new LinkedHashMap<>();
        record.put("APP_NAME", name);
        record.put("ZONE", slug);
        record.put("NODE", z.node());
        record.put("IMAGE", image);
        record.put("PORT", Integer.toString(port));
        record.put("DEPLOY_ID", deployId);
        EnvFile.write(dir.resolve(name + ".env"), record);
        touchActivity(slug);

        return new DeployResult(slug, name, deployId,
                "https://%s.apps.%s.%s/".formatted(name, slug, z.baseDomain()));
    }

    /** {@code POST /undeploy}. */
    public void undeploy(String slug, String name) {
        if (!apps.findByZone(slug).stream().anyMatch(a -> a.name().equals(name))) {
            throw new IllegalArgumentException("zone " + slug + " has no app '" + name + "'");
        }
        Path dir = apps.dir(slug);
        Path rendered = dir.resolve(name + "-compose.yml");
        compose(slug, name, rendered, "down", "-v", "--remove-orphans");
        deleteQuietly(dir.resolve(name + ".env"));
        deleteQuietly(rendered);
        touchActivity(slug);
    }

    // ---------------------------------------------------------------------------

    private DockerCli.Result compose(String slug, String name, Path composeFile, String... args) {
        String node = zones.find(slug).map(Zone::node).orElse("");
        String dockerHost = nodes.find(node).map(n -> n.dockerHost()).orElse("local");
        String composeArg = Files.isRegularFile(composeFile) ? composeFile.toString() : null;
        return docker.compose(dockerHost, "app-" + slug + "-" + name, composeArg, args);
    }

    private void touchActivity(String slug) {
        try {
            Files.writeString(zones.dir(slug).resolve("last-activity"),
                    Long.toString(Instant.now().getEpochSecond()));
        } catch (IOException ignored) {
            // best effort — the sweeper tolerates a missing marker
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // nothing to do
        }
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
