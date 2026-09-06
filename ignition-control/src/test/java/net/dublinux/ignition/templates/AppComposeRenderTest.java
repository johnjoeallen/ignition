package net.dublinux.ignition.templates;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real {@code app-compose.tmpl}, rendered for both channels — the host and
 * the compose-project / router names must differ, everything else must not.
 */
class AppComposeRenderTest {

    private static String render(String host, String project) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("APP_NAME", "web");
        v.put("ZONE_SLUG", "acme");
        v.put("BASE_DOMAIN", "ignition.example");
        v.put("APP_HOST", host);
        v.put("APP_PROJECT", project);
        v.put("APP_IMAGE", "git.acme.ignition.example/acme/web:v1.0.0");
        v.put("APP_PORT", "8080");
        v.put("DEPLOY_ID", "20260906T090000Z");
        v.put("CPU_APP", "0.5");
        v.put("MEM_APP", "256m");
        return new ComposeTemplate().renderApp(v);
    }

    @Test
    void releaseChannel() {
        String out = render("web.apps.acme.ignition.example", "app-acme-web");
        assertThat(out).contains("name: app-acme-web");
        assertThat(out).contains("Host(`web.apps.acme.ignition.example`)");
        assertThat(out).contains("traefik.http.routers.app-acme-web.rule");
        assertThat(out).doesNotContain("${");   // every var resolved
    }

    @Test
    void devChannel() {
        String out = render("web.dev.acme.ignition.example", "app-acme-web-dev");
        assertThat(out).contains("name: app-acme-web-dev");
        assertThat(out).contains("Host(`web.dev.acme.ignition.example`)");
        assertThat(out).contains("traefik.http.routers.app-acme-web-dev.rule");
        assertThat(out).contains("container_name: app-acme-web-dev");
        assertThat(out).contains("com.centurylinklabs.watchtower.enable");
        assertThat(out).doesNotContain("${");
    }
}
