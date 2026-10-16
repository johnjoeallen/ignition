package net.dublinux.ignition.sweep;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;

class AppActivityTest {

    @Test
    void readsRouterNamesFromTraefikAccessLogAndIgnoresHealthProbes() {
        String log = String.join("\n",
                "10.0.0.5 - - [12/Oct/2026:18:01:02 +0000] \"GET / HTTP/2.0\" 200 512 \"-\" \"Mozilla/5.0\" 41 \"app-dragons-cardart@docker\" \"http://172.18.0.9:8080\" 3ms",
                "10.0.0.5 - - [12/Oct/2026:18:01:05 +0000] \"GET /actuator/health HTTP/1.1\" 200 15 \"-\" \"ignition-health-probe\" 42 \"app-dragons-shop-dev@docker\" \"http://172.18.0.11:8080\" 2ms",
                "10.0.0.7 - - [12/Oct/2026:18:02:00 +0000] \"GET /x HTTP/1.1\" 404 19 \"-\" \"curl/8.0\" 43 \"-\" \"-\" 0ms",
                "time=\"2026-10-12T18:00:00Z\" level=info msg=\"Configuration loaded from flags.\"",
                "10.0.0.8 - - [12/Oct/2026:18:03:00 +0000] \"GET / HTTP/1.1\" 200 1 \"https://x@y\" \"a \\\"b\\\"\" 44 \"zone-dragons-forgejo@file\" \"http://forgejo:3000\" 5ms");
        assertEquals(Set.of("app-dragons-cardart", "zone-dragons-forgejo"), AppActivity.routersWithTraffic(log));
    }
}
