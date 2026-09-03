package net.dublinux.ignition.templates;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ComposeTemplateTest {

    @Test
    void substitutesKnownVarsAndLeavesTheRestAlone() {
        String tmpl = "name: app-${ZONE_SLUG}-${APP_NAME}\n"
                + "rule: Host(`${APP_NAME}.apps.${ZONE_SLUG}.${BASE_DOMAIN}`)\n"
                + "shell: echo $HOME and ${NOT_OURS}\n";
        String out = ComposeTemplate.substitute(tmpl, Map.of(
                "ZONE_SLUG", "qb", "APP_NAME", "web", "BASE_DOMAIN", "ignition.example"));

        assertThat(out).contains("name: app-qb-web");
        assertThat(out).contains("Host(`web.apps.qb.ignition.example`)");
        // a plain $VAR and an unknown ${VAR} are untouched
        assertThat(out).contains("echo $HOME and ${NOT_OURS}");
    }
}
