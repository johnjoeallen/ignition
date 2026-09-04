package net.dublinux.ignition.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import net.dublinux.ignition.app.AppService;
import net.dublinux.ignition.app.DeployedApp;
import net.dublinux.ignition.forgejo.ForgejoClient;
import net.dublinux.ignition.release.ReleaseService;
import net.dublinux.ignition.zone.Zone;
import net.dublinux.ignition.zone.ZoneService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * The zone console: Users, Apps (an app <em>is</em> its repo — creating one
 * creates and seeds the repo; Release deploys it), runner restart. The zone is
 * passed as {@code ?z=<slug>} (a platform admin picks it; AUTH-DESIGN step 6
 * will scope this to the caller's zone memberships).
 */
@Controller
public class ZoneConsoleController {

    private final ZoneService zones;
    private final AppService apps;

    public ZoneConsoleController(ZoneService zones, AppService apps) {
        this.zones = zones;
        this.apps = apps;
    }

    /** One row in the Apps table — a repo, plus its live deployment if any. */
    public record AppRow(String name, String htmlUrl, String version, boolean deployed,
                         String image, String url, String deployId) {}

    private static String require(String z) {
        if (z == null || z.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing ?z=<zone>");
        }
        return z.strip();
    }

    @GetMapping("/z")
    public String zone(@RequestParam(name = "z") String z, Model model) {
        String slug = require(z);
        Zone zone = zones.get(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such zone"));
        Map<String, DeployedApp> deployed = apps.listForZone(slug).stream()
                .collect(java.util.stream.Collectors.toMap(DeployedApp::name, a -> a));
        List<AppRow> rows = zones.repos(slug).stream()
                .map(r -> {
                    DeployedApp d = deployed.get(r.name());
                    return new AppRow(r.name(), r.htmlUrl(), r.version(), d != null,
                            d == null ? null : d.image(),
                            d == null ? null : d.url(zone.baseDomain()),
                            d == null ? null : d.deployId());
                })
                .toList();

        model.addAttribute("zoneSlug", slug);
        model.addAttribute("zone", zone);
        model.addAttribute("stack", zones.stack(slug));
        model.addAttribute("apps", rows);
        model.addAttribute("users", zones.users(slug));
        model.addAttribute("botUser", zones.botUser(slug));
        return "zone";
    }

    @PostMapping("/z/users")
    public String createUser(@RequestParam(name = "z") String z,
                             @RequestParam String username,
                             @RequestParam String email,
                             @RequestParam String password) {
        return back(z, zones.createUser(require(z), username, email, password), "user " + username + " created");
    }

    @PostMapping("/z/users/delete")
    public String deleteUser(@RequestParam(name = "z") String z, @RequestParam String login) {
        return back(z, zones.deleteUser(require(z), login), "user " + login + " removed");
    }

    @PostMapping("/z/apps")
    public String createApp(@RequestParam(name = "z") String z,
                            @RequestParam String name) {
        return back(z, zones.createApp(require(z), name),
                "app " + name + " created — clone it, push, then Release");
    }

    @PostMapping("/z/repos/release")
    public String release(@RequestParam(name = "z") String z,
                          @RequestParam(name = "repo") String name,
                          @RequestParam(defaultValue = "auto") String bump) {
        try {
            ReleaseService.Result r = zones.release(require(z), require(z), name, bump);
            String msg = r.ok()
                    ? "released %s %s (%s) — CI is building".formatted(name, r.tag(), r.kind())
                    : "Forgejo said (%d): %s".formatted(r.status(), r.message());
            return redirect(z, msg);
        } catch (IllegalArgumentException e) {
            return redirect(z, e.getMessage());
        }
    }

    @PostMapping("/z/runner/restart")
    public String restartRunner(@RequestParam(name = "z") String z) {
        boolean ok = zones.restartRunner(require(z));
        return redirect(z, ok ? "runner restarted" : "runner restart failed");
    }

    @PostMapping("/z/apps/delete")
    public String deleteApp(@RequestParam(name = "z") String z, @RequestParam String name) {
        try {
            apps.undeploy(require(z), name);
            return redirect(z, "app " + name + " stopped");
        } catch (IllegalArgumentException e) {
            return redirect(z, e.getMessage());
        }
    }

    private String back(String z, ForgejoClient.Response res, String okMsg) {
        return redirect(z, res.ok() ? okMsg
                : "Forgejo said (%d): %s".formatted(res.status(), res.message()));
    }

    private static String redirect(String z, String msg) {
        return "redirect:/z?z=" + enc(z) + "&m=" + enc(msg);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
