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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * The team console: Members (who has Ignition console access to this team, at
 * what role — and, not a separate thing, their Forgejo git login, provisioned
 * automatically from their email), Apps (an app <em>is</em> its repo —
 * creating one creates and seeds the repo; Release deploys it), runner
 * restart. The team is the {@code {slug}} path variable, {@code /zones/<slug>} —
 * access is enforced by {@code ZoneAuthorizationManager} (a platform admin, or a
 * {@code MEMBER}/{@code ZONE_ADMIN} of that team); member-management actions
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

    @GetMapping("/zones/{slug}")
    public String zone(@PathVariable String slug, Model model) {
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

        List<ZoneAccessService.MemberView> members = access.membersOf(slug);

        // Self-heal: a membership row doesn't always come through addMember()
        // below (the zone creator's ZONE_ADMIN row, in particular, is written
        // straight into zone_member by ProvisioningService) — so it's not
        // guaranteed the viewer ever had git access provisioned. Idempotent,
        // like everything else here; cheap enough to just always check on load.
        currentUser.get().ifPresent(me -> {
            if (members.stream().anyMatch(m -> m.userId().equals(me.id()))) {
                zones.ensureGitAccess(slug, me.email(), me.id());
            }
        });

        Map<String, String> gitUsernames = members.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ZoneAccessService.MemberView::email, m -> zones.gitUsername(slug, m.email())));

        java.util.UUID currentUserId = currentUser.get().map(u -> u.id()).orElse(null);
        // Credentials are only ever computed for the viewer's own row — never
        // fetched (let alone decrypted) for anyone else's, so there's nothing
        // to accidentally leak even if the template gating had a bug.
        ZoneService.GitCreds myCreds = members.stream()
                .filter(m -> m.userId().equals(currentUserId))
                .findFirst()
                .map(m -> zones.gitCredentials(slug, gitUsernames.get(m.email()), m.userId()))
                .orElse(null);

        model.addAttribute("zoneSlug", slug);
        model.addAttribute("zone", zone);
        model.addAttribute("stack", zones.stack(slug));
        model.addAttribute("apps", rows);
        model.addAttribute("members", members);
        model.addAttribute("gitUsernames", gitUsernames);
        model.addAttribute("myGitPassword", myCreds == null ? null : myCreds.password());
        model.addAttribute("myGitPat", myCreds == null ? null : myCreds.pat());
        model.addAttribute("canManageMembers", currentUser.isZoneAdmin(slug));
        model.addAttribute("currentUserId", currentUserId);
        return "zone";
    }

    @PostMapping("/zones/{slug}/members")
    public String addMember(@PathVariable String slug,
                            @RequestParam String email,
                            @RequestParam ZoneMember.Role role) {
        requireZoneAdmin(slug);
        try {
            java.util.UUID memberId = access.addMember(slug, email, role);
            String username = zones.ensureGitAccess(slug, email, memberId);
            return redirect(slug, email + " added as " + role.name().toLowerCase()
                    + " — git access as " + username + " (they can see their own password/PAT on this page)");
        } catch (IllegalArgumentException e) {
            return redirect(slug, e.getMessage());
        }
    }

    @PostMapping("/zones/{slug}/members/role")
    public String setMemberRole(@PathVariable String slug,
                                @RequestParam java.util.UUID userId,
                                @RequestParam ZoneMember.Role role) {
        requireZoneAdmin(slug);
        try {
            java.util.UUID actingUserId = currentUser.get().map(u -> u.id()).orElse(null);
            access.setRole(slug, userId, role, actingUserId);
            return redirect(slug, "role changed to " + role.name().toLowerCase());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return redirect(slug, e.getMessage());
        }
    }

    @PostMapping("/zones/{slug}/members/delete")
    public String removeMember(@PathVariable String slug, @RequestParam java.util.UUID userId) {
        requireZoneAdmin(slug);
        try {
            String email = access.emailOf(userId).orElse(null);
            access.removeMember(slug, userId);
            if (email != null) {
                zones.removeGitAccess(slug, zones.gitUsername(slug, email));
            }
            return redirect(slug, "member and their git access removed");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return redirect(slug, e.getMessage());
        }
    }

    @PostMapping("/zones/{slug}/members/reset-git-password")
    public String resetGitPassword(@PathVariable String slug, @RequestParam java.util.UUID userId) {
        requireZoneAdmin(slug);
        String email = access.emailOf(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such member"));
        String username = zones.gitUsername(slug, email);
        String newPassword = zones.resetGitPassword(slug, username, userId);
        return redirect(slug, "git password for " + username + " reset to: " + newPassword
                + " — copy it now, it won't be shown again");
    }

    /** Member-management actions need team-admin rights, not just team access. */
    private void requireZoneAdmin(String slug) {
        if (!currentUser.isZoneAdmin(slug)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only a team admin can manage members");
        }
    }

    @PostMapping("/zones/{slug}/apps")
    public String createApp(@PathVariable String slug, @RequestParam String name) {
        return back(slug, zones.createApp(slug, name),
                "app " + name + " created — clone it, push, then Release");
    }

    @PostMapping("/zones/{slug}/repos/release")
    public String release(@PathVariable String slug,
                          @RequestParam(name = "repo") String name,
                          @RequestParam(defaultValue = "auto") String bump) {
        try {
            ReleaseService.Result r = zones.release(slug, slug, name, bump);
            String msg = r.ok()
                    ? "released %s %s (%s) — CI is building".formatted(name, r.tag(), r.kind())
                    : "Forgejo said (%d): %s".formatted(r.status(), r.message());
            return redirect(slug, msg);
        } catch (IllegalArgumentException e) {
            return redirect(slug, e.getMessage());
        }
    }

    @PostMapping("/zones/{slug}/runner/restart")
    public String restartRunner(@PathVariable String slug) {
        boolean ok = zones.restartRunner(slug);
        return redirect(slug, ok ? "runner restarted" : "runner restart failed");
    }

    @PostMapping("/zones/{slug}/apps/delete")
    public String deleteApp(@PathVariable String slug, @RequestParam String name) {
        try {
            apps.undeploy(slug, name);
            return redirect(slug, "app " + name + " stopped");
        } catch (IllegalArgumentException e) {
            return redirect(slug, e.getMessage());
        }
    }

    private String back(String slug, ForgejoClient.Response res, String okMsg) {
        return redirect(slug, res.ok() ? okMsg
                : "Forgejo said (%d): %s".formatted(res.status(), res.message()));
    }

    private static String redirect(String slug, String msg) {
        return "redirect:/zones/" + enc(slug) + "?m=" + enc(msg);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
