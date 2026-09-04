package net.dublinux.ignition.app;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

import net.dublinux.ignition.docker.DockerCli;
import net.dublinux.ignition.node.NodeRepository;
import net.dublinux.ignition.templates.RenderService;
import net.dublinux.ignition.zone.Zone;
import net.dublinux.ignition.zone.ZoneRepository;
import org.springframework.stereotype.Service;

/**
 * Deployed-app views and the CI bridge. The app is rendered from
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
    private final RenderService render;

    public AppService(AppRepository apps, ZoneRepository zones, NodeRepository nodes,
                      DockerCli docker, RenderService render) {
        this.apps = apps;
        this.zones = zones;
        this.nodes = nodes;
        this.docker = docker;
        this.render = render;
    }

    public List<DeployedApp> list() {
        return apps.findAllOrdered();
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
        String registry = z.gitHost();
        if (!image.startsWith(registry + "/")) {
            throw new IllegalArgumentException("image must be from " + registry + "/");
        }

        String deployId = DEPLOY_ID.format(Instant.now());
        Path composeFile = render.appCompose(slug, name, z.baseDomain(), image, port, deployId);

        DockerCli.Result r = docker.compose(dockerHost(z), "app-" + slug + "-" + name,
                composeFile.toString(), "up", "-d", "--pull", "always", "--remove-orphans");
        if (!r.ok()) {
            throw new DeployException("compose up failed: " + firstLine(r.stderr()));
        }

        DeployedApp app = apps.findByZoneAndName(slug, name).orElse(null);
        if (app == null) {
            app = new DeployedApp(slug, name, z.node(), image, port, deployId);
        } else {
            app.update(z.node(), image, port, deployId);
        }
        apps.save(app);
        touch(z);

        return new DeployResult(slug, name, deployId,
                "https://%s.apps.%s.%s/".formatted(name, slug, z.baseDomain()));
    }

    /** {@code POST /undeploy}. */
    public void undeploy(String slug, String name) {
        DeployedApp app = apps.findByZoneAndName(slug, name)
                .orElseThrow(() -> new IllegalArgumentException("zone " + slug + " has no app '" + name + "'"));
        Zone z = zones.find(slug).orElse(null);
        String baseDomain = z != null ? z.baseDomain() : "";
        Path composeFile = render.appCompose(slug, name, baseDomain, app.image(), app.port(), app.deployId());
        docker.compose(dockerHost(z), "app-" + slug + "-" + name, composeFile.toString(),
                "down", "-v", "--remove-orphans");
        apps.deleteByZoneAndName(slug, name);
        if (z != null) {
            touch(z);
        }
    }

    // ---------------------------------------------------------------------------

    private String dockerHost(Zone z) {
        String node = z == null ? "" : z.node();
        return nodes.findById(node).map(n -> n.dockerHost()).orElse("local");
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
