package net.dublinux.ignition.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import net.dublinux.ignition.app.AppService;
import net.dublinux.ignition.forgejo.ForgejoClient;
import net.dublinux.ignition.release.ReleaseService;
import net.dublinux.ignition.security.IgnitionPrincipal;
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
 * The zone-admin console at {@code admin.<slug>.<BASE_DOMAIN>}. The zone is the
 * one in the caller's token. Ported 1:1 from {@code ign-control.py}'s zone
 * page: Users, Repositories, per-repo Release, runner restart.
 */
@Controller
public class ZoneConsoleController {

    private final ZoneService zones;
    private final AppService apps;

    public ZoneConsoleController(ZoneService zones, AppService apps) {
        this.zones = zones;
        this.apps = apps;
    }

    private String slug() {
        IgnitionPrincipal p = CurrentPrincipal.get();
        if (p == null || p.kind() != IgnitionPrincipal.Kind.ZONE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return p.slug();
    }

    @GetMapping("/z")
    public String zone(Model model) {
        String slug = slug();
        Zone zone = zones.get(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such zone"));
        model.addAttribute("zone", zone);
        model.addAttribute("stack", zones.stack(slug));
        model.addAttribute("apps", apps.listForZone(slug));
        model.addAttribute("users", zones.users(slug));
        model.addAttribute("repos", zones.repos(slug));
        return "zone";
    }

    @PostMapping("/z/users")
    public String createUser(@RequestParam String username,
                             @RequestParam String email,
                             @RequestParam String password) {
        return back(zones.createUser(slug(), username, email, password), "user " + username + " created");
    }

    @PostMapping("/z/users/delete")
    public String deleteUser(@RequestParam String login) {
        return back(zones.deleteUser(slug(), login), "user " + login + " removed");
    }

    @PostMapping("/z/repos")
    public String createRepo(@RequestParam String name,
                             @RequestParam(required = false) String priv) {
        return back(zones.createRepo(slug(), name, "on".equals(priv)), "repo " + name + " created");
    }

    @PostMapping("/z/repos/release")
    public String release(@RequestParam String owner,
                          @RequestParam String repo,
                          @RequestParam(defaultValue = "auto") String bump) {
        try {
            ReleaseService.Result r = zones.release(slug(), owner, repo, bump);
            String msg = r.ok()
                    ? "released %s %s (%s) — CI is building".formatted(repo, r.tag(), r.kind())
                    : "Forgejo said (%d): %s".formatted(r.status(), r.message());
            return "redirect:/z?m=" + enc(msg);
        } catch (IllegalArgumentException e) {
            return "redirect:/z?m=" + enc(e.getMessage());
        }
    }

    @PostMapping("/z/runner/restart")
    public String restartRunner() {
        boolean ok = zones.restartRunner(slug());
        return "redirect:/z?m=" + enc(ok ? "runner restarted" : "runner restart failed");
    }

    @PostMapping("/z/apps/delete")
    public String deleteApp(@RequestParam String name) {
        try {
            apps.undeploy(slug(), name);
            return "redirect:/z?m=" + enc("app " + name + " removed");
        } catch (IllegalArgumentException e) {
            return "redirect:/z?m=" + enc(e.getMessage());
        }
    }

    private String back(ForgejoClient.Response res, String okMsg) {
        return "redirect:/z?m=" + enc(res.ok() ? okMsg
                : "Forgejo said (%d): %s".formatted(res.status(), res.message()));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
