package net.dublinux.ignition.app;

import java.util.List;
import java.util.Map;

import net.dublinux.ignition.config.IgnitionProperties;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppComposeBuilderTest {

    private final IgnitionProperties props = props();
    private final AppComposeBuilder builder = new AppComposeBuilder(props);

    private static IgnitionProperties props() {
        var p = new IgnitionProperties();
        p.setBaseDomain("ignition.example");
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> render(Channel channel, String composeYaml) {
        String out = builder.build("acme", "web", channel,
                "git.acme.ignition.example/acme/web:v1.2.3", 8080, composeYaml);
        return (Map<String, Object>) new Yaml().load(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> svc(Map<String, Object> root, String name) {
        return (Map<String, Object>) ((Map<String, Object>) root.get("services")).get(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> labels(Map<String, Object> svc) {
        Object l = svc.get("labels");
        return l == null ? Map.of() : (Map<String, Object>) l;
    }

    // --- no-file (synthesised) case ------------------------------------------

    @Test
    void synthesisesASingleWebService() {
        var root = render(Channel.RELEASE, null);

        assertThat(root.get("name")).isEqualTo("app-acme-web");
        var services = (Map<String, Object>) root.get("services");
        assertThat(services).containsOnlyKeys("web");

        var web = svc(root, "web");
        assertThat(web.get("image")).isEqualTo("git.acme.ignition.example/acme/web:v1.2.3");
        assertThat(web.get("restart")).isEqualTo("unless-stopped");
        assertThat((List<String>) web.get("networks")).contains("traefik-public", "default");

        var l = labels(web);
        assertThat(l.get("traefik.enable")).isEqualTo("true");
        assertThat(l.get("traefik.http.routers.app-acme-web.rule"))
                .isEqualTo("Host(`web.apps.acme.ignition.example`)");
        assertThat(l.get("traefik.http.services.app-acme-web.loadbalancer.server.port")).isEqualTo("8080");
        assertThat(l.get("com.centurylinklabs.watchtower.enable")).isEqualTo("true");

        assertThat(((Map<String, Object>) root.get("networks"))).containsKey("traefik-public");
    }

    @Test
    void devChannelChangesHostAndProject() {
        var root = render(Channel.DEV, null);
        assertThat(root.get("name")).isEqualTo("app-acme-web-dev");
        assertThat(labels(svc(root, "web")).get("traefik.http.routers.app-acme-web-dev.rule"))
                .isEqualTo("Host(`web.dev.acme.ignition.example`)");
    }

    // --- multi-service transform -------------------------------------------

    private static final String MULTI = """
            services:
              api:
                build: .
                labels: { ignition.web: "true" }
                ports: ["8080:3000"]
                container_name: my-api
              db:
                image: postgres:16-alpine
                volumes: [ "dbdata:/var/lib/postgresql/data" ]
              cache:
                image: redis:7-alpine
            volumes:
              dbdata:
            """;

    @Test
    void routesOnlyTheWebServiceAndIsolatesTheRest() {
        var root = render(Channel.RELEASE, MULTI);

        var api = svc(root, "api");
        assertThat(api).doesNotContainKey("build");            // stripped (image from /deploy)
        assertThat(api).doesNotContainKey("ports");            // host binding stripped
        assertThat(api).doesNotContainKey("container_name");
        assertThat(api.get("image")).isEqualTo("git.acme.ignition.example/acme/web:v1.2.3");
        assertThat((List<String>) api.get("networks")).contains("traefik-public");
        // loadbalancer port taken from the container side of "8080:3000"
        assertThat(labels(api).get("traefik.http.services.app-acme-web.loadbalancer.server.port"))
                .isEqualTo("3000");

        var db = svc(root, "db");
        assertThat(db.get("networks")).isNull();               // default only (implicit)
        assertThat(labels(db)).isEmpty();                      // no router labels
        assertThat(deployLimits(db).get("memory")).isEqualTo("512m");  // svc quota
        assertThat(deployLimits(svc(root, "api")).get("memory")).isEqualTo("1g"); // app quota

        assertThat(((Map<String, Object>) root.get("volumes"))).containsKey("dbdata");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deployLimits(Map<String, Object> svc) {
        var deploy = (Map<String, Object>) svc.get("deploy");
        var resources = (Map<String, Object>) deploy.get("resources");
        return (Map<String, Object>) resources.get("limits");
    }

    @Test
    void declaresANamedVolumeThatTheComposeForgot() {
        var root = render(Channel.RELEASE, """
                services:
                  web:
                    labels: { ignition.web: "true" }
                  db:
                    image: postgres:16-alpine
                    volumes: [ "pgdata:/var/lib/postgresql/data" ]
                """);
        assertThat(((Map<String, Object>) root.get("volumes"))).containsKey("pgdata");
    }

    @Test
    void findsTheWebServiceFromListStyleLabels() {
        var root = render(Channel.RELEASE, """
                services:
                  frontend:
                    image: nginx
                    labels:
                      - "ignition.web=true"
                """);
        assertThat(labels(svc(root, "frontend"))).containsKey("traefik.enable");
    }

    // --- rejects ----------------------------------------------------------

    @Test
    void rejectsPrivileged() {
        assertThatThrownBy(() -> render(Channel.RELEASE, """
                services:
                  web:
                    labels: { ignition.web: "true" }
                    privileged: true
                """))
                .isInstanceOf(ComposeSpecException.class)
                .hasMessageContaining("privileged")
                .hasMessageContaining("compose.override.yaml");
    }

    @Test
    void rejectsBindMounts() {
        assertThatThrownBy(() -> render(Channel.RELEASE, """
                services:
                  web:
                    labels: { ignition.web: "true" }
                    volumes: [ "./src:/app" ]
                """))
                .isInstanceOf(ComposeSpecException.class)
                .hasMessageContaining("bind mount");
    }

    @Test
    void rejectsBuildOnANonWebService() {
        assertThatThrownBy(() -> render(Channel.RELEASE, """
                services:
                  web:
                    labels: { ignition.web: "true" }
                  worker:
                    build: ./worker
                """))
                .isInstanceOf(ComposeSpecException.class)
                .hasMessageContaining("can't build");
    }

    @Test
    void rejectsNoWebService() {
        assertThatThrownBy(() -> render(Channel.RELEASE, """
                services:
                  api: { image: nginx }
                """))
                .isInstanceOf(ComposeSpecException.class)
                .hasMessageContaining("no web service");
    }

    @Test
    void rejectsTwoWebServices() {
        assertThatThrownBy(() -> render(Channel.RELEASE, """
                services:
                  a: { image: nginx, labels: { ignition.web: "true" } }
                  b: { image: nginx, labels: { ignition.web: "true" } }
                """))
                .isInstanceOf(ComposeSpecException.class)
                .hasMessageContaining("more than one");
    }

    @Test
    void rejectsABlockedImage() {
        props.getServices().setBlockedImages(List.of("*badregistry.example*"));
        assertThatThrownBy(() -> render(Channel.RELEASE, """
                services:
                  web: { labels: { ignition.web: "true" } }
                  db:  { image: badregistry.example/pg:16 }
                """))
                .isInstanceOf(ComposeSpecException.class)
                .hasMessageContaining("blocked");
    }

    @Test
    void rejectsInvalidYaml() {
        assertThatThrownBy(() -> render(Channel.RELEASE, "services: [this is: not valid"))
                .isInstanceOf(ComposeSpecException.class);
    }

    @Test
    void globMatch() {
        assertThat(AppComposeBuilder.globMatch("*evil*", "docker.io/evil/thing:1")).isTrue();
        assertThat(AppComposeBuilder.globMatch("docker.io/*", "docker.io/library/postgres")).isTrue();
        assertThat(AppComposeBuilder.globMatch("docker.io/*", "ghcr.io/x/y")).isFalse();
    }
}
