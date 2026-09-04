package net.dublinux.ignition.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import net.dublinux.ignition.app.AppService;
import net.dublinux.ignition.app.DeployedApp;
import net.dublinux.ignition.auth.CurrentUser;
import net.dublinux.ignition.auth.ZoneAccessService;
import net.dublinux.ignition.auth.ZoneMember;
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
 * The team console: Members (who has Ignition console access to this team,
 * and at what role), Users (Forgejo git accounts), Apps (an app <em>is</em>
 * its repo — creating one creates and seeds the repo; Release deploys it),
 * runner restart. The zone is passed as {@code ?z=<slug>} — access is
 * enforced by {@code ZoneAuthorizationManager} (a platform admin, or a
 * {@code MEMBER}/{@code ZONE_ADMIN} of that zone); member-management actions
 * here additionally require {@link CurrentUser#isZoneAdmin}.
 */
@Controller
public class ZoneConsoleController {

    private final ZoneService zones;
    private final AppService apps;
    private final ZoneAccessService access;
    private final CurrentUser currentUser;

    public ZoneConsoleController(ZoneService zones, AppService apps, ZoneAccessService access,
                                 CurrentUser currentUser) {
        this.zones = zones;
        this.apps = apps;
        this.access = access;
        this.currentUser = currentUser;
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
        model.addAttribute("members", access.membersOf(slug));
        model.addAttribute("canManageMembers", currentUser.isZoneAdmin(slug));
        return "zone";
    }

    @PostMapping("/z/members")
    public String addMember(@RequestParam(name = "z") String z,
                            @RequestParam String email,
                            @RequestParam ZoneMember.Role role) {
        String slug = requireZoneAdmin(z);
        try {
            access.addMember(slug, email, role);
            return redirect(slug, email + " added as " + role.name().toLowerCase());
        } catch (IllegalArgumentException e) {
            return redirect(slug, e.getMessage());
        }
    }

    @PostMapping("/z/members/role")
    public String setMemberRole(@RequestParam(name = "z") String z,
                                @RequestParam java.util.UUID userId,
                                @RequestParam ZoneMember.Role role) {
        String slug = requireZoneAdmin(z);
        try {
            access.setRole(slug, userId, role);
            return redirect(slug, "role changed to " + role.name().toLowerCase());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return redirect(slug, e.getMessage());
        }
    }

    @PostMapping("/z/members/delete")
    public String removeMember(@RequestParam(name = "z") String z, @RequestParam java.util.UUID userId) {
        String slug = requireZoneAdmin(z);
        try {
            access.removeMember(slug, userId);
            return redirect(slug, "member removed");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return redirect(slug, e.getMessage());
        }
    }

    /** Member-management actions need team-admin rights, not just team access. */
    private String requireZoneAdmin(String z) {
        String slug = require(z);
        if (!currentUser.isZoneAdmin(slug)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only a team admin can manage members");
        }
        return slug;
    }

    @PostMapping("/z/users")
    public String createUser(@RequestParam(name = "z") String z,
                             @RequestParam String email,
                             @RequestParam String password) {
        String slug = requireZoneAdmin(z);
        return back(slug, zones.createUser(slug, email, password), "user " + email + " created");
    }

    @PostMapping("/z/users/delete")
    public String deleteUser(@RequestParam(name = "z") String z, @RequestParam String login) {
        String slug = requireZoneAdmin(z);
        return back(slug, zones.deleteUser(slug, login), "user " + login + " removed");
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
