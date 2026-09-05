package net.dublinux.ignition.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import net.dublinux.ignition.app.AppService;
import net.dublinux.ignition.app.DeployedApp;
import net.dublinux.ignition.auth.CurrentUser;
import net.dublinux.ignition.auth.MailService;
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
 * restart. The team is the {@code {slug}} path variable, {@code /teams/<slug>} —
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
    private final MailService mail;

    public ZoneConsoleController(ZoneService zones, AppService apps, ZoneAccessService access,
                                 CurrentUser currentUser, MailService mail) {
        this.zones = zones;
        this.apps = apps;
        this.access = access;
        this.currentUser = currentUser;
        this.mail = mail;
    }

    /** One row in the Apps table — a repo, plus its live deployment if any. */
    public record AppRow(String name, String description, String version, boolean deployed,
                         String image, String url, String deployId) {}

    @GetMapping("/teams/{slug}")
    public String zone(@PathVariable String slug, Model model) {
        Zone zone = zones.get(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such zone"));
        Map<String, DeployedApp> deployed = apps.listForZone(slug).stream()
                .collect(java.util.stream.Collectors.toMap(DeployedApp::name, a -> a));
        List<AppRow> rows = zones.repos(slug).stream()
                .map(r -> {
                    DeployedApp d = deployed.get(r.name());
                    return new AppRow(r.name(), r.description(), r.version(), d != null,
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
        var me = members.stream().filter(m -> m.userId().equals(currentUserId)).findFirst();
        String myGitUsername = me.map(m -> gitUsernames.get(m.email())).orElse(null);
        ZoneService.GitCreds myCreds = me
                .map(m -> zones.gitCredentials(slug, gitUsernames.get(m.email()), m.userId()))
                .orElse(null);

        model.addAttribute("zoneSlug", slug);
        model.addAttribute("zone", zone);
        model.addAttribute("stack", zones.stack(slug));
        model.addAttribute("apps", rows);
        model.addAttribute("members", members);
        model.addAttribute("gitUsernames", gitUsernames);
        model.addAttribute("myGitUsername", myGitUsername);
        model.addAttribute("myGitPassword", myCreds == null ? null : myCreds.password());
        model.addAttribute("myGitPat", myCreds == null ? null : myCreds.pat());
        model.addAttribute("canManageMembers", currentUser.isZoneAdmin(slug));
        model.addAttribute("currentUserId", currentUserId);
        return "zone";
    }

    @PostMapping("/teams/{slug}/members")
    public String addMember(@PathVariable String slug,
                            @RequestParam String email,
                            @RequestParam ZoneMember.Role role) {
        requireZoneAdmin(slug);
        try {
            java.util.UUID memberId = access.addMember(slug, email, role);
            String username = zones.ensureGitAccess(slug, email, memberId);
            mail.sendAddedToTeam(email, slug, role.name().toLowerCase());
            return redirect(slug, email + " added as " + role.name().toLowerCase()
                    + " — git access as " + username + " (they can see their own password/PAT on this page)");
        } catch (IllegalArgumentException e) {
            return redirect(slug, e.getMessage());
        }
    }

    @PostMapping("/teams/{slug}/members/role")
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

    @PostMapping("/teams/{slug}/members/delete")
    public String removeMember(@PathVariable String slug, @RequestParam java.util.UUID userId) {
        requireZoneAdmin(slug);
        try {
            String email = access.emailOf(userId).orElse(null);
            access.removeMember(slug, userId);
            if (email != null) {
                zones.removeGitAccess(slug, zones.gitUsername(slug, email));
                mail.sendRemovedFromTeam(email, slug);
            }
            return redirect(slug, "member and their git access removed");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return redirect(slug, e.getMessage());
        }
    }

    @PostMapping("/teams/{slug}/members/reset-git-password")
    public String resetGitPassword(@PathVariable String slug, @RequestParam java.util.UUID userId) {
        requireSelfOrZoneAdmin(slug, userId);
        String email = access.emailOf(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such member"));
        String username = zones.gitUsername(slug, email);
        String newPassword = zones.resetGitPassword(slug, username, userId);
        return redirect(slug, "git password for " + username + " reset to: " + newPassword
                + " — copy it now, it won't be shown again");
    }

    /** Anyone can regenerate their own PAT; a team admin can also regenerate someone else's. */
    @PostMapping("/teams/{slug}/members/reset-pat")
    public String resetPat(@PathVariable String slug, @RequestParam java.util.UUID userId) {
        requireSelfOrZoneAdmin(slug, userId);
        String email = access.emailOf(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such member"));
        String username = zones.gitUsername(slug, email);
        String newPat = zones.resetPat(slug, username, userId);
        return redirect(slug, "personal access token for " + username + " regenerated: " + newPat
                + " — copy it now, it won't be shown again");
    }

    /** Member-management actions need team-admin rights, not just team access. */
    private void requireZoneAdmin(String slug) {
        if (!currentUser.isZoneAdmin(slug)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only a team admin can manage members");
        }
    }

    /** Resetting your own credentials never needs admin rights; resetting someone else's does. */
    private void requireSelfOrZoneAdmin(String slug, java.util.UUID targetUserId) {
        boolean isSelf = currentUser.get().map(u -> u.id().equals(targetUserId)).orElse(false);
        if (!isSelf) {
            requireZoneAdmin(slug);
        }
    }

    @PostMapping("/teams/{slug}/apps")
    public String createApp(@PathVariable String slug, @RequestParam String name,
                            @RequestParam(required = false) String description) {
        return back(slug, zones.createApp(slug, name, description),
                "app " + name + " created — clone it, push, then Release");
    }

    @PostMapping("/teams/{slug}/repos/release")
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

    /**
     * One row per issue — its branch (always derivable, never stored), and
     * whichever open PR has that branch as its head, if one's been opened yet.
     */
    public record IssueRow(int number, String title, String htmlUrl, String branchName,
                           Integer prNumber, String prHtmlUrl, boolean prMergeable) {}

    /** A repo's own page, managed entirely through its issues — see {@link net.dublinux.ignition.zone.ZoneService#createIssue}. */
    @GetMapping("/teams/{slug}/repos/{repo}")
    public String repo(@PathVariable String slug, @PathVariable String repo, Model model) {
        List<ZoneService.PullView> pulls = zones.pulls(slug, repo);
        Map<String, ZoneService.PullView> pullByBranch = pulls.stream()
                .collect(java.util.stream.Collectors.toMap(ZoneService.PullView::head, p -> p, (a, b) -> a));
        List<IssueRow> issueRows = zones.issues(slug, repo).stream()
                .map(i -> {
                    var pr = pullByBranch.get(i.branchName());
                    return new IssueRow(i.number(), i.title(), i.htmlUrl(), i.branchName(),
                            pr == null ? null : pr.number(), pr == null ? null : pr.htmlUrl(),
                            pr != null && pr.mergeable());
                })
                .toList();

        ZoneService.RepoView info = zones.repoInfo(slug, repo);

        model.addAttribute("zoneSlug", slug);
        model.addAttribute("repoName", repo);
        model.addAttribute("repoInfo", info);
        model.addAttribute("issueRows", issueRows);
        return "repo";
    }

    @PostMapping("/teams/{slug}/repos/{repo}/description")
    public String updateDescription(@PathVariable String slug, @PathVariable String repo,
                                    @RequestParam(required = false, defaultValue = "") String description) {
        var res = zones.updateRepoDescription(slug, repo, description);
        return redirectRepo(slug, repo, res.ok() ? "description updated"
                : "Forgejo said (%d): %s".formatted(res.status(), res.message()));
    }

    /** Opening an issue creates its branch too — see {@link net.dublinux.ignition.zone.ZoneService#createIssue}. */
    @PostMapping("/teams/{slug}/repos/{repo}/issues")
    public String createIssue(@PathVariable String slug, @PathVariable String repo,
                              @RequestParam String title, @RequestParam(required = false) String body) {
        var r = zones.createIssue(slug, repo, callerEmail(), callerId(), title, body);
        String msg = !r.ok() ? "Forgejo said: " + r.message()
                : r.branchName() != null ? "issue #" + r.number() + " opened, branch " + r.branchName() + " created"
                : "issue #" + r.number() + " opened, but " + r.message();
        return redirectRepo(slug, repo, msg);
    }

    /** Opens a PR for this issue's branch into main — Closes #n in the body closes the issue on merge. */
    @PostMapping("/teams/{slug}/repos/{repo}/issues/{number}/pr")
    public String openPrForIssue(@PathVariable String slug, @PathVariable String repo,
                                 @PathVariable int number, @RequestParam String title) {
        var res = zones.openPrForIssue(slug, repo, callerEmail(), callerId(), number, title);
        return redirectRepo(slug, repo, res.ok() ? "PR opened for issue #" + number
                : "Forgejo said (%d): %s".formatted(res.status(), res.message()));
    }

    /** Merges the open PR for this issue's branch — found by the branch, not a stored PR number. */
    @PostMapping("/teams/{slug}/repos/{repo}/issues/{number}/merge")
    public String mergePrForIssue(@PathVariable String slug, @PathVariable String repo,
                                  @PathVariable int number, @RequestParam String title) {
        try {
            var res = zones.mergePrForIssue(slug, repo, callerEmail(), callerId(), number, title);
            String msg;
            if (res.ok()) {
                msg = "issue #" + number + "'s PR merged";
            } else if (res.status() == 405) {
                // Forgejo's merge endpoint returns this exact 405 for more than
                // one underlying state, and doesn't distinguish them in the
                // response: still computing mergeability right after the PR
                // opened (transient — waiting resolves it), or a branch with no
                // actual diff from main (permanent — Forgejo's own UI shows only
                // "Close" for this one, never "Merge"; no amount of waiting
                // fixes it). We can't tell which one this is from here, so
                // don't promise it'll resolve on its own — point at the PR
                // itself instead, where Forgejo's own UI does show which case it is.
                msg = "Forgejo won't merge this yet (405) — open the PR itself to see why: "
                        + "either it's still computing mergeability (wait and retry), or the branch "
                        + "has no commits beyond main yet (push some, or just close it)";
            } else {
                msg = "Forgejo said (%d): %s".formatted(res.status(), res.message());
            }
            return redirectRepo(slug, repo, msg);
        } catch (IllegalArgumentException e) {
            return redirectRepo(slug, repo, e.getMessage());
        }
    }

    /** Closes the PR without merging — for when there's nothing to merge (Forgejo's own UI offers only this then). */
    @PostMapping("/teams/{slug}/repos/{repo}/issues/{number}/close")
    public String closePrForIssue(@PathVariable String slug, @PathVariable String repo,
                                  @PathVariable int number, @RequestParam String title) {
        try {
            var res = zones.closePrForIssue(slug, repo, callerEmail(), callerId(), number, title);
            return redirectRepo(slug, repo, res.ok()
                    ? "issue #" + number + " closed — PR closed, branch deleted"
                    : "Forgejo said (%d): %s".formatted(res.status(), res.message()));
        } catch (IllegalArgumentException e) {
            return redirectRepo(slug, repo, e.getMessage());
        }
    }

    private String callerEmail() {
        return currentUser.get().map(u -> u.email()).orElse("");
    }

    private java.util.UUID callerId() {
        return currentUser.get().map(u -> u.id()).orElse(null);
    }

    private static String redirectRepo(String slug, String repo, String msg) {
        return "redirect:/teams/" + enc(slug) + "/repos/" + enc(repo) + "?m=" + enc(msg);
    }

    @PostMapping("/teams/{slug}/runner/restart")
    public String restartRunner(@PathVariable String slug) {
        boolean ok = zones.restartRunner(slug);
        return redirect(slug, ok ? "runner restarted" : "runner restart failed");
    }

    @PostMapping("/teams/{slug}/apps/delete")
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
        return "redirect:/teams/" + enc(slug) + "?m=" + enc(msg);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
