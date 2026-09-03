package net.dublinux.ignition.zone;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import net.dublinux.ignition.docker.DockerCli;
import net.dublinux.ignition.forgejo.ForgejoClient;
import net.dublinux.ignition.node.NodeRepository;
import net.dublinux.ignition.release.ReleaseService;
import org.springframework.stereotype.Service;

/**
 * Zone lifecycle + the zone-admin surface. Lifecycle (provision / move /
 * destroy) lands with {@code ProvisioningService} (DESIGN.md steps 5–6); the
 * console actions below proxy the zone's own Forgejo admin API or a
 * project-scoped {@code docker compose}.
 */
@Service
public class ZoneService {

    private final ZoneRepository zones;
    private final NodeRepository nodes;
    private final ForgejoClient forgejo;
    private final ReleaseService releases;
    private final DockerCli docker;

    public ZoneService(ZoneRepository zones, NodeRepository nodes, ForgejoClient forgejo,
                       ReleaseService releases, DockerCli docker) {
        this.zones = zones;
        this.nodes = nodes;
        this.forgejo = forgejo;
        this.releases = releases;
        this.docker = docker;
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
        String node = zones.find(slug).map(Zone::node).orElse("");
        String dockerHost = nodes.find(node).map(n -> n.dockerHost()).orElse("local");
        String composeFile = zones.dir(slug).resolve("docker-compose.yml").toString();
        return docker.compose(dockerHost, "zone-" + slug, composeFile, args);
    }
}
