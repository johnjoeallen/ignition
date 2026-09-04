package net.dublinux.ignition.zone;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import net.dublinux.ignition.app.AppRepository;
import net.dublinux.ignition.app.DeployedApp;
import net.dublinux.ignition.config.IgnitionProperties;
import net.dublinux.ignition.docker.DockerCli;
import net.dublinux.ignition.forgejo.ForgejoClient;
import net.dublinux.ignition.node.NodeRepository;
import net.dublinux.ignition.provisioning.ProvisioningStatusRepository;
import net.dublinux.ignition.release.ReleaseService;
import net.dublinux.ignition.templates.RenderService;
import net.dublinux.ignition.traefik.TraefikDynamicConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Zone lifecycle + the zone-admin surface. Provisioning lives in
 * {@code ProvisioningService}; this covers destroy / move and the console
 * actions (proxied Forgejo admin API, or a project-scoped {@code docker
 * compose}).
 */
@Service
public class ZoneService {

    private static final Logger log = LoggerFactory.getLogger(ZoneService.class);

    private final ZoneRepository zones;
    private final NodeRepository nodes;
    private final AppRepository apps;
    private final ForgejoClient forgejo;
    private final ReleaseService releases;
    private final DockerCli docker;
    private final RenderService render;
    private final TraefikDynamicConfig traefik;
    private final ProvisioningStatusRepository statuses;
    private final IgnitionProperties props;

    public ZoneService(ZoneRepository zones, NodeRepository nodes, AppRepository apps,
                       ForgejoClient forgejo, ReleaseService releases, DockerCli docker,
                       RenderService render, TraefikDynamicConfig traefik,
                       ProvisioningStatusRepository statuses, IgnitionProperties props) {
        this.zones = zones;
        this.nodes = nodes;
        this.apps = apps;
        this.forgejo = forgejo;
        this.releases = releases;
        this.docker = docker;
        this.render = render;
        this.traefik = traefik;
        this.statuses = statuses;
        this.props = props;
    }

    public List<Zone> list() {
        return zones.findAll();
    }

    public Optional<Zone> get(String slug) {
        return zones.find(slug);
    }

    public String zoneToken(String slug) {
        return zones.secret(slug, "zone-token");
    }

    public String deployToken(String slug) {
        return zones.secret(slug, "deploy-token");
    }

    // --- destroy / move ----------------------------------------------------

    /**
     * Tear down a zone's stack and every app it deployed, on its node. With
     * {@code keepState} the {@code zone} row and its Traefik router are left in
     * place (used by {@link #prepareMove}).
     */
    public void destroy(String slug, boolean keepState) {
        Zone zone = zones.find(slug)
                .orElseThrow(() -> new IllegalArgumentException("no such zone: " + slug));
        String dockerHost = dockerHost(zone);

        for (DeployedApp app : apps.findByZone(slug)) {
            Path composeFile = render.appCompose(slug, app.name(), zone.baseDomain(),
                    app.image(), app.port(), app.deployId());
            docker.compose(dockerHost, "app-" + slug + "-" + app.name(), composeFile.toString(),
                    "down", "-v", "--remove-orphans");
            if (!keepState) {
                apps.deleteByZoneAndName(slug, app.name());
            }
        }

        if (!keepState) {
            traefik.removeZoneRouter(slug);
        }

        Path compose = render.zoneCompose(zone);
        DockerCli.Result down = docker.compose(dockerHost, "zone-" + slug, compose.toString(),
                "down", "-v", "--remove-orphans");
        if (!down.ok()) {
            DockerCli.Result ps = docker.docker(dockerHost, List.of(
                    "ps", "-aq", "--filter", "name=^zone-" + slug + "-"));
            for (String id : ps.stdout().split("\\s+")) {
                if (!id.isBlank()) {
                    docker.docker(dockerHost, List.of("rm", "-f", id));
                }
            }
        }

        if (keepState) {
            log.info("zone {} torn down (state kept)", slug);
        } else {
            zones.delete(slug);                    // cascades zone_secret + app rows
            statuses.deleteById(slug);
            wipeWorkDir(slug);
            log.info("zone {} destroyed", slug);
        }
    }

    /**
     * Teardown-keep-state, drop the per-node credentials, and point the zone at
     * the target node. The caller then re-provisions on the target.
     */
    public void prepareMove(String slug, String targetNode) {
        Zone z = zones.find(slug)
                .orElseThrow(() -> new IllegalArgumentException("no such zone: " + slug));
        if (nodes.findById(targetNode).isEmpty()) {
            throw new IllegalArgumentException("no such node: " + targetNode);
        }
        if (targetNode.equals(z.node())) {
            throw new IllegalStateException("zone " + slug + " is already on " + targetNode);
        }
        destroy(slug, true);
        for (String s : List.of("runner-secret",
                "forgejo_username", "forgejo_password", "forgejo_url", "forgejo_token")) {
            zones.deleteSecret(slug, s);
        }
        wipeWorkDir(slug);
        z.setNode(targetNode);
        zones.save(z);
        log.info("zone {} prepared to move -> {}", slug, targetNode);
    }

    private String dockerHost(Zone zone) {
        return nodes.findById(zone.node()).map(n -> n.dockerHost()).orElse("local");
    }

    private void wipeWorkDir(String slug) {
        Path dir = props.zoneWorkDir(slug);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException("deleting " + p, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("removing " + dir, e);
        }
    }

    // --- Forgejo users -------------------------------------------------------

    public record ForgejoUser(String login, String email, boolean admin) {}

    public List<ForgejoUser> users(String slug) {
        var res = forgejo.get(slug, "/admin/users?limit=50");
        List<ForgejoUser> out = new ArrayList<>();
        if (res.ok() && res.body() != null && res.body().isArray()) {
            for (JsonNode u : res.body()) {
                out.add(new ForgejoUser(u.path("login").asText(""), u.path("email").asText(""),
                        u.path("is_admin").asBoolean(false)));
            }
        }
        return out;
    }

    public ForgejoClient.Response createUser(String slug, String username, String email, String password) {
        return forgejo.post(slug, "/admin/users", Map.of(
                "username", username, "email", email, "password", password,
                "must_change_password", false));
    }

    public ForgejoClient.Response deleteUser(String slug, String login) {
        return forgejo.delete(slug, "/admin/users/" + login);
    }

    // --- Forgejo repos -----------------------------------------------------

    public record RepoView(String owner, String name, String fullName, String htmlUrl, String version) {}

    public List<RepoView> repos(String slug) {
        var res = forgejo.get(slug, "/repos/search?limit=50");
        List<RepoView> out = new ArrayList<>();
        JsonNode data = res.body() == null ? null : res.body().get("data");
        if (data != null && data.isArray()) {
            for (JsonNode r : data) {
                String owner = r.path("owner").path("login").asText(
                        r.path("full_name").asText("/").split("/")[0]);
                String name = r.path("name").asText("");
                int[] v = ReleaseService.latestSemver(
                        forgejo.get(slug, "/repos/%s/%s/tags?limit=50".formatted(owner, name)).body());
                String version = (v[0] == 0 && v[1] == 0 && v[2] == 0)
                        ? "no releases yet" : "v%d.%d.%d".formatted(v[0], v[1], v[2]);
                out.add(new RepoView(owner, name, r.path("full_name").asText(""),
                        r.path("html_url").asText(""), version));
            }
        }
        return out;
    }

    public ForgejoClient.Response createRepo(String slug, String name, boolean priv) {
        return forgejo.post(slug, "/admin/users/zoneadmin/repos", Map.of(
                "name", name, "private", priv, "auto_init", true));
    }

    public ReleaseService.Result release(String slug, String owner, String repo, String kind) {
        return releases.cut(slug, owner, repo, kind);
    }

    // --- runner / stack (project-scoped compose) --------------------------

    public boolean restartRunner(String slug) {
        return compose(slug, "restart", "runner").ok();
    }

    public String stack(String slug) {
        DockerCli.Result r = compose(slug, "ps", "--format", "{{.Service}}={{.State}}");
        return r.ok() && !r.stdout().isBlank() ? r.stdout().strip().replace("\n", " ") : "—";
    }

    private DockerCli.Result compose(String slug, String... args) {
        Zone zone = zones.find(slug).orElseThrow(() -> new IllegalArgumentException("no such zone: " + slug));
        Path compose = render.zoneCompose(zone);
        return docker.compose(dockerHost(zone), "zone-" + slug, compose.toString(), args);
    }
}
