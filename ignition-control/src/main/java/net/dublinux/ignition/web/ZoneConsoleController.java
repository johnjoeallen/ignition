package net.dublinux.ignition.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import net.dublinux.ignition.app.AppConfig;
import net.dublinux.ignition.app.AppConfigRepository;
import net.dublinux.ignition.app.AppSecret;
import net.dublinux.ignition.app.AppSecretService;
import net.dublinux.ignition.app.SecretScope;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
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
    private final AppConfigRepository appConfigs;
    private final AppSecretService appSecrets;
    private final ZoneAccessService access;
    private final CurrentUser currentUser;
    private final MailService mail;

    public ZoneConsoleController(ZoneService zones, AppService apps, AppConfigRepository appConfigs,
                                 AppSecretService appSecrets, ZoneAccessService access,
                                 CurrentUser currentUser, MailService mail) {
        this.zones = zones;
        this.apps = apps;
        this.appConfigs = appConfigs;
        this.appSecrets = appSecrets;
        this.access = access;
        this.currentUser = currentUser;
        this.mail = mail;
    }

    /** One row in the Apps table — a repo, plus its live deployment if any. */
    public record AppRow(String name, String description, String version, boolean deployed,
                         boolean running, String image, String url, String deployId, String idleTtl,
                         String pending) {}

    /** One environment's secrets on the app page — names and metadata only, never values. */
    public record SecretSection(String scope, String title, String hint, String restart, List<AppSecret> items) {}

    /** Restart-button state for an environment: {@code none}, {@code undeployed}, {@code current} or {@code pending}. */
    private String restartState(String slug, String repo, SecretScope scope, java.time.Instant deployedAt) {
        if (deployedAt == null) {
            return "undeployed";
        }
        return appSecrets.changedAt(slug, repo, scope).filter(deployedAt::isBefore).isPresent() ? "pending" : "current";
    }

    /** One row in the Dev table — an app's deploy-from-main deployment. */
    public record DevRow(String name, String url, String image, String deployedAt, boolean running) {}

    /** {@code channel} is {@code release} or {@code dev}; the page keys rows on both. */
    public record AppStatus(String name, boolean deployed, boolean running, String channel) {}

    @GetMapping("/teams/{slug}")
    public String zone(@PathVariable String slug, Model model) {
        Zone zone = zones.get(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such zone"));
        Map<String, DeployedApp> deployed = apps.listForZone(slug).stream()
                .collect(java.util.stream.Collectors.toMap(DeployedApp::name, a -> a));
        Map<String, Boolean> live = apps.liveStates(slug);
        Map<String, Integer> ttls = new java.util.HashMap<>();
        appConfigs.findByZone(slug).forEach(c -> ttls.put(c.name(), c.idleTtlMinutes()));
        List<AppRow> rows = zones.repos(slug).stream()
                .map(r -> {
                    DeployedApp d = deployed.get(r.name());
                    // r.version() is the latest git tag — it exists before CI has built and deployed
                    // anything. Show what is actually deployed; the tag only as "pending" until it is.
                    boolean tagged = r.version().startsWith("v");
                    String deployedVersion = d == null ? null : imageVersion(d.image());
                    String shown = d == null ? (tagged ? "not deployed" : r.version())
                            : deployedVersion != null ? deployedVersion : "deployed";
                    String pending = tagged && !r.version().equals(deployedVersion) ? r.version() : null;
                    return new AppRow(r.name(), r.description(), shown, d != null,
                            d != null && live.getOrDefault(d.name(), d.running()),
                            d == null ? null : d.image(),
                            d == null ? null : d.url(zone.baseDomain()),
                            d == null ? null : d.deployId(),
                            ttlValue(ttls.get(r.name())), pending);
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
        addOwnGitCreds(slug, currentUserId, model);

        model.addAttribute("zoneSlug", slug);
        model.addAttribute("zone", zone);
        model.addAttribute("stack", zones.stack(slug));
        model.addAttribute("apps", rows);
        Map<String, Boolean> devLive = apps.devLiveStates(slug);
        java.time.format.DateTimeFormatter at = DateTimeFormatter.ofPattern("d MMM HH:mm")
                .withZone(java.time.ZoneOffset.UTC);
        model.addAttribute("devs", apps.devDeploymentsForZone(slug).stream()
                .map(d -> new DevRow(d.name(), d.url(zone.baseDomain()), d.image(),
                        at.format(d.deployedAt()) + " UTC", devLive.getOrDefault(d.name(), d.running())))
                .toList());
        model.addAttribute("members", members);
        model.addAttribute("gitUsernames", gitUsernames);
        model.addAttribute("canManageMembers", currentUser.isZoneAdmin(slug));
        model.addAttribute("currentUserId", currentUserId);
        return "zone";
    }

    /** Small polling payload used to keep Start/Stop controls current. */
    @GetMapping("/teams/{slug}/apps/status")
    @ResponseBody
    public List<AppStatus> appStatus(@PathVariable String slug) {
        Map<String, Boolean> live = apps.liveStates(slug);
        Map<String, Boolean> devLive = apps.devLiveStates(slug);
        List<AppStatus> out = new java.util.ArrayList<>(apps.listForZone(slug).stream()
                .map(a -> new AppStatus(a.name(), true, live.getOrDefault(a.name(), a.running()), "release"))
                .toList());
        apps.devDeploymentsForZone(slug).forEach(d ->
                out.add(new AppStatus(d.name(), true, devLive.getOrDefault(d.name(), d.running()), "dev")));
        return out;
    }

    /** {@code vX.Y.Z} from an image ref like {@code git.x/slug/app:v1.2.3}, else null (e.g. a sha tag). */
    private static String imageVersion(String image) {
        int i = image == null ? -1 : image.lastIndexOf(':');
        String tag = i < 0 ? "" : image.substring(i + 1);
        return tag.matches("v\\d+\\.\\d+\\.\\d+") ? tag : null;
    }

    /** Form value for the idle-TTL select: blank = platform default, else minutes ({@code 0} = never). */
    private static String ttlValue(Integer minutes) {
        return minutes == null ? "" : minutes.toString();
    }

    /**
     * Team admin: how long this app may go without traffic before the idle sweep
     * stops it. Stored by app name, so it can be set before the first release;
     * blank = platform default, 0 = never.
     */
    @PostMapping("/teams/{slug}/apps/idle-ttl")
    public String setIdleTtl(@PathVariable String slug, @RequestParam String name,
                             @RequestParam(defaultValue = "") String minutes,
                             @RequestParam(value = "from", required = false) String from) {
        if (!currentUser.isZoneAdmin(slug)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only a team admin can change an app's idle time");
        }
        try {
            Integer m = minutes.isBlank() ? null : Integer.valueOf(minutes.strip());
            if (m != null && m < 0) {
                throw new IllegalArgumentException("idle time can't be negative");
            }
            AppConfig c = appConfigs.findById(new net.dublinux.ignition.app.DeployedApp.Key(slug, name))
                    .orElseGet(() -> new AppConfig(slug, name, null));
            c.setIdleTtlMinutes(m);
            appConfigs.save(c);
            return appBack(slug, name, from, "idle time for " + name + " saved");
        } catch (IllegalArgumentException e) {
            return appBack(slug, name, from, e.getMessage());
        }
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
                    + " — git access as " + username + " (they can see their own password/PAT on the team page and any app's page)");
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
    public Object resetGitPassword(@PathVariable String slug, @RequestParam java.util.UUID userId,
                                   @RequestParam(required = false) String repo,
                                   @RequestHeader(value = "Accept", required = false) String accept) {
        requireSelfOrZoneAdmin(slug, userId);
        String email = access.emailOf(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such member"));
        String username = zones.gitUsername(slug, email);
        String newPassword = zones.resetGitPassword(slug, username, userId);
        return credsReset(slug, repo, accept, userId, newPassword,
                "git password for " + username + " regenerated");
    }

    /** Anyone can regenerate their own PAT; a team admin can also regenerate someone else's. */
    @PostMapping("/teams/{slug}/members/reset-pat")
    public Object resetPat(@PathVariable String slug, @RequestParam java.util.UUID userId,
                           @RequestParam(required = false) String repo,
                           @RequestHeader(value = "Accept", required = false) String accept) {
        requireSelfOrZoneAdmin(slug, userId);
        String email = access.emailOf(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such member"));
        String username = zones.gitUsername(slug, email);
        String newPat = zones.resetPat(slug, username, userId);
        return credsReset(slug, repo, accept, userId, newPat,
                "personal access token for " + username + " regenerated");
    }

    /**
     * The page's own script asks for JSON and swaps the new value into the DOM —
     * no redirect, so the secret is only ever in a response body, never a URL.
     * Only the owner gets the value back; without JS it falls back to a redirect
     * whose message points at where the (page-rendered) credential is.
     */
    private Object credsReset(String slug, String repo, String accept, java.util.UUID userId,
                              String secret, String what) {
        boolean self = currentUser.get().map(u -> u.id().equals(userId)).orElse(false);
        if (accept != null && accept.contains("application/json")) {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("ok", true);
            body.put("message", what);
            body.put("value", self ? secret : null);
            return org.springframework.http.ResponseEntity.ok()
                    .header("Cache-Control", "no-store").body(body);
        }
        return backToCreds(slug, repo, what + " — " + whereToFind(userId) + ". The old one no longer works.");
    }

    /** Credentials are never put in a URL or shown to anyone but their owner. */
    private String whereToFind(java.util.UUID userId) {
        boolean self = currentUser.get().map(u -> u.id().equals(userId)).orElse(false);
        return self ? "the new one is shown above" : "they can see the new one on their own page";
    }

    /** The credentials now live on each app's page — return there if a reset came from one. */
    private static String backToCreds(String slug, String repo, String msg) {
        return repo == null || repo.isBlank() ? redirect(slug, msg) : redirectRepo(slug, repo, msg);
    }

    /**
     * The viewer's own git login for {@code slug} — username, password, PAT —
     * onto the model as {@code myGit*}. Shown on both the team page and each
     * app page. Only ever computed for the viewer's own membership row, never
     * fetched (let alone decrypted) for anyone else's, so there's nothing to
     * leak even if the template gating had a bug; a platform admin who isn't a
     * member of this team gets nulls.
     */
    private void addOwnGitCreds(String slug, java.util.UUID currentUserId, Model model) {
        var me = access.membersOf(slug).stream()
                .filter(m -> m.userId().equals(currentUserId)).findFirst();
        String username = me.map(m -> zones.gitUsername(slug, m.email())).orElse(null);
        ZoneService.GitCreds creds = me
                .map(m -> zones.gitCredentials(slug, username, m.userId()))
                .orElse(null);
        model.addAttribute("myGitUsername", username);
        model.addAttribute("myGitPassword", creds == null ? null : creds.password());
        model.addAttribute("myGitPat", creds == null ? null : creds.pat());
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

        // "You have unreleased work" — the merged PRs / commits on main that no
        // release tag covers yet, plus the closed issues among them. Makes a
        // merge-that-did-nothing visible instead of silent.
        ReleaseService.Pending pending = zones.pendingRelease(slug, repo);
        List<ZoneService.IssueView> unreleasedIssues =
                zones.unreleasedClosedIssues(slug, repo, pending.lastReleaseAt());

        java.util.UUID currentUserId = currentUser.get().map(u -> u.id()).orElse(null);
        addOwnGitCreds(slug, currentUserId, model);

        model.addAttribute("zoneSlug", slug);
        model.addAttribute("repoName", repo);
        model.addAttribute("repoInfo", info);
        model.addAttribute("idleTtl", ttlValue(appConfigs.findById(
                new net.dublinux.ignition.app.DeployedApp.Key(slug, repo))
                .map(AppConfig::idleTtlMinutes).orElse(null)));
        model.addAttribute("canManageApp", currentUser.isZoneAdmin(slug));
        var releaseApp = apps.listForZone(slug).stream().filter(x -> x.name().equals(repo)).findFirst();
        String releaseBase = zones.get(slug).map(Zone::baseDomain).orElse("");
        model.addAttribute("releaseDeployed", releaseApp.isPresent());
        model.addAttribute("releaseRunning", releaseApp
                .map(x -> apps.liveStates(slug).getOrDefault(x.name(), x.running())).orElse(false));
        model.addAttribute("releaseUrl", releaseApp.map(x -> x.url(releaseBase)).orElse(null));
        model.addAttribute("statusUrl", "/teams/" + slug + "/apps/status");
        java.time.Instant releaseAt = apps.listForZone(slug).stream()
                .filter(x -> x.name().equals(repo)).findFirst().map(DeployedApp::deployedAt).orElse(null);
        java.time.Instant devAt = apps.devDeployment(slug, repo)
                .map(net.dublinux.ignition.app.DevDeployment::deployedAt).orElse(null);
        // previews: judged on the oldest one, since a change must reach every preview
        java.time.Instant previewAt = apps.previewsForApp(slug, repo).stream()
                .map(DeployedApp::deployedAt).min(java.util.Comparator.naturalOrder()).orElse(null);
        model.addAttribute("secretSections", List.of(
                new SecretSection("release", "Release",
                        "Used by tagged releases, at " + repo + ".apps." + slug + "…",
                        restartState(slug, repo, SecretScope.RELEASE, releaseAt),
                        appSecrets.list(slug, repo, SecretScope.RELEASE)),
                new SecretSection("dev", "Dev (main)",
                        "Used by Deploy from main, at " + repo + ".dev." + slug + "…",
                        restartState(slug, repo, SecretScope.DEV, devAt),
                        appSecrets.list(slug, repo, SecretScope.DEV)),
                new SecretSection("preview", "PR previews",
                        "Used by every PR preview of this app. Restart redeploys all the previews running now.",
                        restartState(slug, repo, SecretScope.PREVIEW, previewAt),
                        appSecrets.list(slug, repo, SecretScope.PREVIEW))));
        model.addAttribute("canManageSecrets", currentUser.isZoneAdmin(slug));
        // info.version() is the latest git tag, which exists before CI has built anything — show what is deployed
        boolean tagged = info.version().startsWith("v");
        String deployedVersion = apps.listForZone(slug).stream()
                .filter(a -> a.name().equals(repo)).findFirst()
                .map(a -> imageVersion(a.image())).orElse(null);
        model.addAttribute("versionShown", deployedVersion != null ? deployedVersion
                : tagged ? "not deployed" : info.version());
        model.addAttribute("versionPending", tagged && !info.version().equals(deployedVersion) ? info.version() : null);
        model.addAttribute("issueRows", issueRows);
        model.addAttribute("pending", pending);
        model.addAttribute("unreleasedIssues", unreleasedIssues);
        var dev = apps.devDeployment(slug, repo);
        model.addAttribute("devUrl", dev
                .map(d -> d.url(zones.get(slug).map(Zone::baseDomain).orElse("")))
                .orElse(null));
        model.addAttribute("devDeployedAt", dev
                .map(d -> DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(java.time.ZoneOffset.UTC)
                        .format(d.deployedAt()) + " UTC")
                .orElse(null));
        model.addAttribute("devRunning", dev.map(d -> apps.devLive(slug, repo, d.running())).orElse(false));
        String baseDomain = zones.get(slug).map(Zone::baseDomain).orElse("");
        Map<String, Boolean> previewLive = apps.liveStates(slug);
        model.addAttribute("previews", apps.previewsForApp(slug, repo).stream()
                .<Map<String, Object>>map(p -> Map.of("name", p.name(), "url", p.url(baseDomain),
                        "number", p.name().substring(p.name().lastIndexOf("-pr-") + 4),
                        "running", previewLive.getOrDefault(p.name(), p.running())))
                .toList());
        model.addAttribute("currentUserId", currentUserId);
        return "repo";
    }

    /** Set (or replace) a write-only runtime secret for one environment. Team admins only; the value is never echoed back. */
    @PostMapping("/teams/{slug}/repos/{repo}/secrets")
    public String setSecret(@PathVariable String slug, @PathVariable String repo,
                            @RequestParam String scope, @RequestParam String name, @RequestParam String value) {
        requireSecretsAdmin(slug);
        try {
            SecretScope sc = SecretScope.parse(scope);
            appSecrets.put(slug, repo, sc, name.strip(), value,
                    currentUser.get().map(u -> u.email()).orElse(null));
            return redirectRepo(slug, repo, "secret " + name.strip() + " saved for " + sc.key()
                    + " — it applies at the next deploy, or press Restart with new secrets");
        } catch (IllegalArgumentException e) {
            return redirectRepo(slug, repo, e.getMessage());
        }
    }

    @PostMapping("/teams/{slug}/repos/{repo}/secrets/delete")
    public String deleteSecret(@PathVariable String slug, @PathVariable String repo,
                               @RequestParam String scope, @RequestParam String name) {
        requireSecretsAdmin(slug);
        try {
            appSecrets.delete(slug, repo, SecretScope.parse(scope), name);
            return redirectRepo(slug, repo, "secret " + name + " deleted — running containers keep it until the next deploy");
        } catch (IllegalArgumentException e) {
            return redirectRepo(slug, repo, e.getMessage());
        }
    }

    /** Redeploy one environment with the same image so its changed secrets take effect now. */
    @PostMapping("/teams/{slug}/repos/{repo}/secrets/apply")
    public String applySecrets(@PathVariable String slug, @PathVariable String repo, @RequestParam String scope) {
        requireSecretsAdmin(slug);
        try {
            SecretScope sc = SecretScope.parse(scope);
            apps.redeploy(slug, repo, sc);
            return redirectRepo(slug, repo, sc.key() + " restarted with its current secrets");
        } catch (RuntimeException e) {
            return redirectRepo(slug, repo, "apply failed: " + e.getMessage());
        }
    }

    private void requireSecretsAdmin(String slug) {
        if (!currentUser.isZoneAdmin(slug)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only a team admin can manage an app's secrets");
        }
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
                msg = "issue #" + number + "'s PR merged into main — not deployed yet; "
                        + "click a Release button to ship it";
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

    /** Dev actions fired from the team page's Dev table go back there; the repo page's go back to the repo. */
    private static String devBack(String slug, String repo, String from, String msg) {
        return "team".equals(from) ? redirect(slug, repo + ": " + msg) : redirectRepo(slug, repo, msg);
    }

    private static String redirectRepo(String slug, String repo, String msg) {
        return "redirect:/teams/" + enc(slug) + "/repos/" + enc(repo) + "?m=" + enc(msg);
    }

    @PostMapping("/teams/{slug}/runner/restart")
    public String restartRunner(@PathVariable String slug) {
        boolean ok = zones.restartRunner(slug);
        return redirect(slug, ok ? "runner restarted" : "runner restart failed");
    }

    /**
     * Stop a running app's containers ({@code docker compose stop}) — the stack,
     * its volumes and the app row all stay; Start brings it back with no new
     * release. Idempotent, so clicking Stop on an already-stopped app is a no-op.
     */
    @PostMapping("/teams/{slug}/apps/stop")
    public String stopApp(@PathVariable String slug, @RequestParam String name,
                          @RequestParam(value = "from", required = false) String from,
                          @RequestParam(value = "repo", required = false) String repo) {
        try {
            apps.stop(slug, name);
            return appBack(slug, repo != null ? repo : name, from, "app " + name + " stopped");
        } catch (RuntimeException e) {
            return appBack(slug, repo != null ? repo : name, from, e.getMessage());
        }
    }

    /** Restart a stopped app ({@code docker compose start}). */
    @PostMapping("/teams/{slug}/apps/start")
    public String startApp(@PathVariable String slug, @RequestParam String name,
                           @RequestParam(value = "from", required = false) String from,
                           @RequestParam(value = "repo", required = false) String repo) {
        try {
            apps.start(slug, name);
            return appBack(slug, repo != null ? repo : name, from, "app " + name + " started");
        } catch (RuntimeException e) {
            return appBack(slug, repo != null ? repo : name, from, e.getMessage());
        }
    }

    /**
     * Redeploy one deployment of an app with the image it already runs — re-reads
     * its compose.yaml/.env/secrets and recreates the containers (also starts a
     * stopped one). Managed from the app's own page.
     */
    @PostMapping("/teams/{slug}/repos/{repo}/redeploy")
    public String redeployApp(@PathVariable String slug, @PathVariable String repo, @RequestParam String scope) {
        try {
            SecretScope sc = SecretScope.parse(scope);
            apps.redeploy(slug, repo, sc);
            return redirectRepo(slug, repo, sc.key() + " redeployed");
        } catch (RuntimeException e) {
            return redirectRepo(slug, repo, "redeploy failed: " + e.getMessage());
        }
    }

    /** Redeploy one PR preview at its PR's current head (picks up changed secrets, compose.yaml and .env). */
    @PostMapping("/teams/{slug}/repos/{repo}/previews/redeploy")
    public String redeployPreview(@PathVariable String slug, @PathVariable String repo, @RequestParam String name) {
        try {
            apps.redeployPreview(slug, repo, name);
            return redirectRepo(slug, repo, "preview " + name + " redeployed");
        } catch (RuntimeException e) {
            return redirectRepo(slug, repo, "redeploy failed: " + e.getMessage());
        }
    }

    /** Tear down one PR preview of {@code repo} (name must be {@code <repo>-pr-<n>}); the PR itself is untouched. */
    @PostMapping("/teams/{slug}/repos/{repo}/previews/delete")
    public String deletePreview(@PathVariable String slug, @PathVariable String repo, @RequestParam String name) {
        if (!name.matches(java.util.regex.Pattern.quote(repo) + "-pr-\\d+")) {
            return redirectRepo(slug, repo, "not a PR preview of " + repo);
        }
        try {
            apps.undeploy(slug, name);
            return redirectRepo(slug, repo, "preview " + name + " deleted");
        } catch (RuntimeException e) {
            return redirectRepo(slug, repo, e.getMessage());
        }
    }

    /** Release Stop/Start fired from the team page go back there; from the app's page, back to the app. */
    private static String appBack(String slug, String name, String from, String msg) {
        return "repo".equals(from) ? redirectRepo(slug, name, msg) : redirect(slug, msg);
    }

    /**
     * "Deploy from main" — build and ship the current {@code main} HEAD to
     * {@code <name>.dev.<slug>.<domain>}. Dispatches the repo's deploy workflow;
     * CI does the build + {@code POST /deploy?channel=dev}. Any team member can,
     * same as Release.
     */
    @PostMapping("/teams/{slug}/repos/dev")
    public String deployFromMain(@PathVariable String slug, @RequestParam("repo") String name) {
        var res = zones.deployFromMain(slug, name);
        return redirectRepo(slug, name, res.ok()
                ? "building main — the dev site refreshes in a minute or two"
                : "couldn't start the build (%d): %s".formatted(res.status(), res.message()));
    }

    /** Stop the dev deployment's containers ({@code docker compose stop}) — stack and volumes kept. Idempotent. */
    @PostMapping("/teams/{slug}/repos/dev/stop")
    public String stopDev(@PathVariable String slug, @RequestParam("repo") String name,
                          @RequestParam(value = "from", required = false) String from) {
        try {
            apps.stopDev(slug, name);
            return devBack(slug, name, from, "dev deployment stopped");
        } catch (RuntimeException e) {
            return devBack(slug, name, from, e.getMessage());
        }
    }

    /** Restart a stopped dev deployment ({@code docker compose start}). */
    @PostMapping("/teams/{slug}/repos/dev/start")
    public String startDev(@PathVariable String slug, @RequestParam("repo") String name,
                           @RequestParam(value = "from", required = false) String from) {
        try {
            apps.startDev(slug, name);
            return devBack(slug, name, from, "dev deployment started");
        } catch (RuntimeException e) {
            return devBack(slug, name, from, e.getMessage());
        }
    }

    /** Tear the dev deployment down entirely ({@code docker compose down -v} + row delete). The release stays untouched. */
    @PostMapping("/teams/{slug}/repos/dev/delete")
    public String deleteDev(@PathVariable String slug, @RequestParam("repo") String name,
                            @RequestParam(value = "from", required = false) String from) {
        try {
            apps.undeploy(slug, name, net.dublinux.ignition.app.Channel.DEV);
            return devBack(slug, name, from, "dev deployment deleted");
        } catch (RuntimeException e) {
            return devBack(slug, name, from, e.getMessage());
        }
    }

    /**
     * Delete the app entirely — an app <em>is</em> its repo, so this stops any
     * live deployment and then removes the Forgejo repo (code, issues, PRs,
     * releases). Irreversible; the template guards it with a confirm.
     */
    @PostMapping("/teams/{slug}/apps/delete")
    public String deleteApp(@PathVariable String slug, @RequestParam String name) {
        try {
            apps.undeploy(slug, name);
        } catch (IllegalArgumentException notDeployed) {
            // nothing running — go straight to removing the repo
        }
        try {
            apps.undeploy(slug, name, net.dublinux.ignition.app.Channel.DEV);
        } catch (IllegalArgumentException noDev) {
            // no dev deployment — fine
        }
        appConfigs.deleteByZoneAndName(slug, name);
        appSecrets.deleteAll(slug, name);
        var res = zones.deleteApp(slug, name);
        return redirect(slug, res.ok() || res.status() == 404
                ? "app " + name + " deleted"
                : "deleting " + name + " failed (" + res.status() + "): " + res.message());
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
