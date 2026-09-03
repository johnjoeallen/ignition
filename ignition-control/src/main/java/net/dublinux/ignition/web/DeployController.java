package net.dublinux.ignition.web;

import java.util.Map;

import net.dublinux.ignition.security.IgnitionPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The CI bridge. Bearer = the zone's {@code deploy-token}. Contract preserved
 * from {@code ign-control.py}: {@code POST /deploy {app, image, port}} and
 * {@code POST /undeploy {app}}. Implemented once the compose runner lands
 * (DESIGN.md step 4).
 */
@RestController
public class DeployController {

    @PostMapping("/deploy")
    public ResponseEntity<Map<String, Object>> deploy(@RequestBody Map<String, Object> body) {
        return notYet("deploy", body);
    }

    @PostMapping("/undeploy")
    public ResponseEntity<Map<String, Object>> undeploy(@RequestBody Map<String, Object> body) {
        return notYet("undeploy", body);
    }

    private ResponseEntity<Map<String, Object>> notYet(String op, Map<String, Object> body) {
        IgnitionPrincipal p = CurrentPrincipal.get();
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
                "error", op + " not implemented yet in ignition-control",
                "zone", p == null ? "" : String.valueOf(p.slug()),
                "received", body));
    }
}
