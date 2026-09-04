package net.dublinux.ignition.web;

import java.util.Map;

import net.dublinux.ignition.app.AppService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The CI bridge. Bearer = the zone's {@code deploy-token} (enforced by
 * {@code SecurityConfig}: {@code POST /deploy|/undeploy} needs {@code DEPLOY}).
 * Contract preserved from {@code ign-control.py}:
 * {@code POST /deploy {app, image, port}}, {@code POST /undeploy {app}}.
 */
@RestController
public class DeployController {

    private final AppService apps;

    public DeployController(AppService apps) {
        this.apps = apps;
    }

    @PostMapping("/deploy")
    public ResponseEntity<?> deploy(@RequestBody Map<String, Object> body) {
        String slug = zoneOr403();
        String app = str(body.get("app"));
        String image = str(body.get("image"));
        int port = intOr(body.get("port"), 8080);
        try {
            AppService.DeployResult r = apps.deploy(slug, app, image, port);
            return ResponseEntity.ok(Map.of(
                    "ok", true, "zone", r.zone(), "app", r.app(),
                    "deploy_id", r.deployId(), "url", r.url()));
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (AppService.DeployException e) {
            return err(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    @PostMapping("/undeploy")
    public ResponseEntity<?> undeploy(@RequestBody Map<String, Object> body) {
        String slug = zoneOr403();
        String app = str(body.get("app"));
        try {
            apps.undeploy(slug, app);
            return ResponseEntity.ok(Map.of("ok", true, "removed", app));
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static String zoneOr403() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean deploy = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_DEPLOY"));
        if (!deploy) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return auth.getName();   // the zone slug (set by DeployTokenFilter)
    }

    private static ResponseEntity<Map<String, Object>> err(HttpStatus status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg == null ? "" : msg));
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().strip();
    }

    private static int intOr(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? fallback : Integer.parseInt(o.toString().strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
