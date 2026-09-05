package net.dublinux.ignition.zone;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import net.dublinux.ignition.app.AppRepository;
import net.dublinux.ignition.app.DeployedApp;
import net.dublinux.ignition.config.IgnitionProperties;
import net.dublinux.ignition.docker.DockerCli;
import net.dublinux.ignition.forgejo.ForgejoClient;
import net.dublinux.ignition.node.NodeRepository;
import net.dublinux.ignition.provisioning.ProvisioningStatusRepository;
import net.dublinux.ignition.release.ReleaseService;
import net.dublinux.ignition.templates.RenderService;
import net.dublinux.ignition.traefik.TraefikDynamicConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Zone lifecycle + the zone-admin surface. Provisioning lives in
 * {@code ProvisioningService}; this covers destroy / move and the console
 * actions (proxied Forgejo admin API, or a project-scoped {@code docker
 * compose}).
 */
@Service
public class ZoneService {

    private static final Logger log = LoggerFactory.getLogger(ZoneService.class);

    private final ZoneRepository zones;
    private final NodeRepository nodes;
    private final AppRepository apps;
    private final ForgejoClient forgejo;
    private final ReleaseService releases;
    private final DockerCli docker;
    private final RenderService render;
    private final TraefikDynamicConfig traefik;
    private final ProvisioningStatusRepository statuses;
    private final IgnitionProperties props;

    public ZoneService(ZoneRepository zones, NodeRepository nodes, AppRepository apps,
                       ForgejoClient forgejo, ReleaseService releases, DockerCli docker,
                       RenderService render, TraefikDynamicConfig traefik,
                       ProvisioningStatusRepository statuses, IgnitionProperties props) {
        this.zones = zones;
        this.nodes = nodes;
        this.apps = apps;
        this.forgejo = forgejo;
        this.releases = releases;
        this.docker = docker;
        this.render = render;
        this.traefik = traefik;
        this.statuses = statuses;
        this.props = props;
    }

    public List<Zone> list() {
        return zones.findAll();
    }

    public Optional<Zone> get(String slug) {
        return zones.find(slug);
    }

    public String zoneToken(String slug) {
        return zones.secret(slug, "zone-token");
    }

    public String deployToken(String slug) {
        return zones.secret(slug, "deploy-token");
    }

    /** The Forgejo bot account's username for this zone — not a real login. */
    public String botUser(String slug) {
        return zones.secret(slug, "forgejo_username");
    }

    // --- destroy / move ----------------------------------------------------

    /**
     * Tear down a zone's stack and every app it deployed, on its node. With
     * {@code keepState} the {@code zone} row and its Traefik router are left in
     * place (used by {@link #prepareMove}).
     */
    public void destroy(String slug, boolean keepState) {
        Zone zone = zones.find(slug)
                .orElseThrow(() -> new IllegalArgumentException("no such zone: " + slug));
        String dockerHost = dockerHost(zone);

        for (DeployedApp app : apps.findByZone(slug)) {
            Path composeFile = render.appCompose(slug, app.name(), zone.baseDomain(),
                    app.image(), app.port(), app.deployId());
            docker.compose(dockerHost, "app-" + slug + "-" + app.name(), composeFile.toString(),
                    "down", "-v", "--remove-orphans");
            if (!keepState) {
                apps.deleteByZoneAndName(slug, app.name());
            }
        }

        if (!keepState) {
            traefik.removeZoneRouter(slug);
        }

        Path compose = render.zoneCompose(zone);
        DockerCli.Result down = docker.compose(dockerHost, "zone-" + slug, compose.toString(),
                "down", "-v", "--remove-orphans");
        if (!down.ok()) {
            DockerCli.Result ps = docker.docker(dockerHost, List.of(
                    "ps", "-aq", "--filter", "name=^zone-" + slug + "-"));
            for (String id : ps.stdout().split("\\s+")) {
                if (!id.isBlank()) {
                    docker.docker(dockerHost, List.of("rm", "-f", id));
                }
            }
        }

        if (keepState) {
            log.info("zone {} torn down (state kept)", slug);
        } else {
            zones.delete(slug);                    // cascades zone_secret + app rows
            statuses.deleteById(slug);
            wipeWorkDir(slug);
            log.info("zone {} destroyed", slug);
        }
    }

    /**
     * Teardown-keep-state, drop the per-node credentials, and point the zone at
     * the target node. The caller then re-provisions on the target.
     */
    public void prepareMove(String slug, String targetNode) {
        Zone z = zones.find(slug)
                .orElseThrow(() -> new IllegalArgumentException("no such zone: " + slug));
        if (nodes.findById(targetNode).isEmpty()) {
            throw new IllegalArgumentException("no such node: " + targetNode);
        }
        if (targetNode.equals(z.node())) {
            throw new IllegalStateException("zone " + slug + " is already on " + targetNode);
        }
        destroy(slug, true);
        for (String s : List.of("runner-secret",
                "forgejo_username", "forgejo_password", "forgejo_url", "forgejo_token")) {
            zones.deleteSecret(slug, s);
        }
        wipeWorkDir(slug);
        z.setNode(targetNode);
        zones.save(z);
        log.info("zone {} prepared to move -> {}", slug, targetNode);
    }

    private String dockerHost(Zone zone) {
        return nodes.findById(zone.node()).map(n -> n.dockerHost()).orElse("local");
    }

    private void wipeWorkDir(String slug) {
        Path dir = props.zoneWorkDir(slug);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException("deleting " + p, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("removing " + dir, e);
        }
    }

    // --- git access — not a separate concept from team membership. Every
    // team member's Forgejo login is their sanitized email, provisioned (and
    // torn down) automatically alongside their Ignition membership; there's
    // no separate "add a Forgejo user" step or password to hand out. ---

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    /**
     * Every non-admin scope Forgejo has — a member's PAT should be able to do
     * anything a normal user can do through the API, the same as they can
     * through the web UI with their password. In particular {@code
     * write:package}: the container registry (git.<slug>...) authenticates
     * with a token exactly like this one, so this is what lets a member's
     * own PAT stand in for the manually-created {@code FORGEJO_TOKEN} repo
     * secret the seeded deploy.yml already accepts (optional there — it
     * falls back to the per-run Actions token — but a member using their own
     * PAT there, or from their own machine, needs the scope to push).
     * {@code write:X} implies read:X too. Deliberately excludes the one
     * admin-only scope (site-wide admin) — this is a normal user's token.
     */
    private static final List<String> PAT_SCOPES = List.of(
            "write:activitypub", "write:issue", "write:misc", "write:notification",
            "write:organization", "write:package", "write:repository", "write:user");

    /**
     * The Forgejo login a member's email actually resolved to. Not just
     * {@code usernameFromEmail(email)} — a username collision means the real
     * login can carry a numeric suffix (see {@link #ensureGitAccess}), so
     * this reads back the mapping {@code ensureGitAccess} recorded, falling
     * back to the bare sanitized form only for an email nothing's ever
     * provisioned yet (nothing to look up).
     */
    public String gitUsername(String slug, String email) {
        String stored = zones.secret(slug, "git_user_" + email.toLowerCase(Locale.ROOT));
        return stored.isBlank() ? usernameFromEmail(email) : stored;
    }

    /**
     * The stored credentials for a git login — null fields if never
     * provisioned/minted. Decrypted with a key derived from {@code userId}
     * (PBKDF2, see {@link UserSecretCipher}), not the shared zone-secret
     * key every other {@code zone_secret} row uses — {@code userId} must be
     * the same account the credentials were written for, or decryption
     * fails outright rather than silently returning someone else's value.
     */
    public record GitCreds(String password, String pat) {}

    public GitCreds gitCredentials(String slug, String username, java.util.UUID userId) {
        String pw = zones.userSecret(slug, "git_pw_" + username, userId);
        String pat = zones.userSecret(slug, "git_pat_" + username, userId);
        return new GitCreds(pw.isBlank() ? null : pw, pat.isBlank() ? null : pat);
    }

    /**
     * Ensures a Forgejo account exists for this member, in the zone's org,
     * with a password and a personal access token — both stored (encrypted
     * with a key derived from their own {@code userId}, so they can be shown
     * back to that person later, not just once at creation. Idempotent —
     * safe to call every time someone's added, or re-added. A username
     * collision (two different emails sanitizing to the same login) is
     * resolved the way Gmail suggests alternatives on a taken address —
     * append a number and try again — not with a separator, so {@code alice}
     * collides to {@code alice2}, {@code alice3}, ….
     */
    public String ensureGitAccess(String slug, String email, java.util.UUID userId) {
        String emailKey = "git_user_" + email.toLowerCase(Locale.ROOT);
        String base = usernameFromEmail(email);
        String username = base;
        for (int suffix = 2; suffix <= 20; suffix++) {
            var existing = forgejo.get(slug, "/users/" + username);
            if (!existing.ok()) {
                break; // free to use
            }
            if (email.equalsIgnoreCase(existing.body().path("email").asText(""))) {
                return adoptExistingAccount(slug, emailKey, username, userId);
            }
            username = base + suffix;
        }
        String password = randBase64(18);
        var res = forgejo.post(slug, "/admin/users", Map.of(
                "username", username, "email", email, "password", password,
                "must_change_password", false));
        if (!res.ok()) {
            // The loop above only probed candidate *usernames* — but Forgejo
            // enforces *email* uniqueness account-wide, so it can reject a
            // username that really was free, because the same person already
            // has an account under some other login entirely (e.g. from
            // before this zone existed, or before the sanitizer picked this
            // particular username). Recognize that specific rejection and go
            // find the real account instead of just failing outright and
            // leaving this person with team membership but no working login.
            if (res.status() == 422 && res.message() != null && res.message().contains("e-mail already in use")) {
                String realUsername = findByEmail(slug, email);
                if (realUsername != null) {
                    log.warn("zone {}: {} already has a Forgejo account as {} (found by email — the {} we "
                            + "tried was free but the address wasn't) — adopting it instead of failing",
                            slug, email, realUsername, username);
                    return adoptExistingAccount(slug, emailKey, realUsername, userId);
                }
            }
            log.warn("zone {}: could not create Forgejo account {} for {} ({}): {}",
                    slug, username, email, res.status(), res.message());
            return username;
        }
        ensureOrgMembership(slug, username);
        zones.putSecret(slug, emailKey, username);
        zones.putUserSecret(slug, "git_pw_" + username, password, userId);
        log.info("zone {}: git password generated for {} ({}): {}", slug, username, email, password);
        ensurePat(slug, username, userId);
        return username;
    }

    /** An account that already exists (found either by username or by email) — assert membership and fresh, readable creds. */
    private String adoptExistingAccount(String slug, String emailKey, String username, java.util.UUID userId) {
        ensureOrgMembership(slug, username);
        zones.putSecret(slug, emailKey, username);
        // The account predates us knowing its password (this ignition-control
        // never set one it kept, or the row is stale — including a row this
        // user's key can no longer decrypt, e.g. written before per-user
        // encryption existed) — Forgejo won't hand back an existing password
        // either way, so the only way to have one on file to show is to set a
        // fresh one now. userSecret(), not hasSecret(): a row can exist and
        // still not be readable with this user's key.
        if (zones.userSecret(slug, "git_pw_" + username, userId).isBlank()) {
            resetGitPassword(slug, username, userId);
        }
        ensurePat(slug, username, userId);
        return username;
    }

    /** Forgejo's admin user search, filtered to an exact email match — for the "email already in use" fallback above. */
    private String findByEmail(String slug, String email) {
        var res = forgejo.get(slug, "/admin/users?limit=50&q="
                + java.net.URLEncoder.encode(email, StandardCharsets.UTF_8));
        if (!res.ok() || res.body() == null || !res.body().isArray()) {
            return null;
        }
        for (JsonNode u : res.body()) {
            if (email.equalsIgnoreCase(u.path("email").asText(""))) {
                return u.path("login").asText(null);
            }
        }
        return null;
    }

    /**
     * Mints a personal access token for a git login if one isn't already
     * stored and readable. Self-healing, like everything else here — checked
     * by actually decrypting (userSecret), not just row-presence (hasSecret):
     * a row can exist and still not be readable with this user's key, e.g. if
     * it predates per-user encryption. In that case the old token still works
     * fine for git (we just can't show it), so it's deleted and replaced
     * rather than left as silent clutter alongside the new one.
     *
     * <p>The delete is unconditional, not gated on us having a stored row —
     * Forgejo enforces a unique token <em>name</em> per user, so a token
     * named {@code ignition} can exist on its side from an earlier attempt
     * that got this far and then failed before we ever stored anything (a
     * response we couldn't parse, a save that never happened, ...). Trying
     * to create over that gets a 400 "access token name has been used
     * already" — seen live — forever, since our own bookkeeping has no
     * record of it to react to. A delete of a token that isn't there is a
     * harmless no-op, so it's cheaper to always clear the way than to keep
     * guessing whether it's needed from state that can't see Forgejo's side.
     */
    private void ensurePat(String slug, String username, java.util.UUID userId) {
        if (!zones.userSecret(slug, "git_pat_" + username, userId).isBlank()) {
            return;
        }
        mintPat(slug, username, userId);
    }

    /** Mints a fresh PAT unconditionally, replacing whatever's on file — see {@link #ensurePat} for the gated version. */
    private void mintPat(String slug, String username, java.util.UUID userId) {
        forgejo.deleteBasicAuth(slug, "/users/" + username + "/tokens/ignition?sudo=" + username);
        // sudo: the bot is a Forgejo site admin (see ProvisioningService), so this
        // executes as `username` rather than as the bot itself. Basic auth, not the
        // bot's usual token — Forgejo refuses token auth on this endpoint outright
        // (see ForgejoClient#postBasicAuth).
        log.info("zone {}: minting PAT for {} (scopes {})", slug, username, PAT_SCOPES);
        var res = forgejo.postBasicAuth(slug, "/users/" + username + "/tokens?sudo=" + username,
                Map.of("name", "ignition", "scopes", PAT_SCOPES));
        if (!res.ok()) {
            log.warn("zone {}: could not mint a PAT for {} ({}): {}", slug, username, res.status(), res.message());
            return;
        }
        // Forgejo returns the raw token only on creation, as `sha1` (legacy field name, carried from Gitea).
        String token = res.body().path("sha1").asText("");
        if (token.isBlank()) {
            token = res.body().path("token").asText("");
        }
        if (token.isBlank()) {
            log.warn("zone {}: PAT create for {} returned 2xx but no sha1/token field — full body: {}",
                    slug, username, res.body());
            return;
        }
        zones.putUserSecret(slug, "git_pat_" + username, token, userId);
        log.info("zone {}: PAT minted for {}: {}", slug, username, token);
    }

    /** Regenerates a member's PAT on demand — the old one stops working immediately (Forgejo deletes it first). */
    public String resetPat(String slug, String username, java.util.UUID userId) {
        mintPat(slug, username, userId);
        return zones.userSecret(slug, "git_pat_" + username, userId);
    }

    private void ensureOrgMembership(String slug, String username) {
        int owners = ensureOrg(slug);
        if (owners > 0) {
            forgejo.put(slug, "/teams/" + owners + "/members/" + username, Map.of());
        }
    }

    /** A fresh random git password for a member, stored (encrypted per-user) so it can be shown back to them. */
    public String resetGitPassword(String slug, String username, java.util.UUID userId) {
        String pw = randBase64(18);
        forgejo.patch(slug, "/admin/users/" + username, Map.of("password", pw, "must_change_password", false));
        zones.putUserSecret(slug, "git_pw_" + username, pw, userId);
        log.info("zone {}: git password reset for {}: {}", slug, username, pw);
        return pw;
    }

    public void removeGitAccess(String slug, String username) {
        forgejo.delete(slug, "/admin/users/" + username);
        zones.deleteSecret(slug, "git_pw_" + username);
        zones.deleteSecret(slug, "git_pat_" + username);
    }

    private static String randBase64(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    /**
     * The mailbox part of an email (before {@code @}), lowercased and
     * stripped down to what Forgejo accepts in a username (letters, digits,
     * {@code .-_}), with the domain dropped entirely and leading/trailing/
     * repeated separators collapsed.
     */
    private static String usernameFromEmail(String email) {
        String mailbox = email.split("@", 2)[0];
        String cleaned = mailbox.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "-")
                .replaceAll("[._-]{2,}", "-")
                .replaceAll("^[._-]+|[._-]+$", "");
        return cleaned.isBlank() ? "user" : cleaned;
    }

    // --- the zone's org — one org per zone (name = slug) so every zone user
    // can see every repo, instead of everything piling up under the bot account ---

    private static final String OWNERS_TEAM = "Owners";

    /**
     * Create the zone's org if it doesn't exist yet (idempotent — the bot
     * account, which owns every zone, already owns it after the first call).
     * Returns the {@code Owners} team id, or -1 if it couldn't be found.
     */
    private int ensureOrg(String slug) {
        var created = forgejo.post(slug, "/orgs", Map.of("username", slug, "visibility", "public"));
        if (!created.ok()) {
            log.debug("zone {}: org create said ({}): {} — fine if it already exists",
                    slug, created.status(), created.message());
        }
        // idempotent — also fixes an org created before this was "public"
        var patched = forgejo.patch(slug, "/orgs/" + slug, Map.of("visibility", "public"));
        if (patched.ok()) {
            String actual = patched.body() == null ? "" : patched.body().path("visibility").asText("");
            if (!"public".equals(actual)) {
                log.warn("zone {}: PATCH visibility=public returned 200 but org now reports '{}'"
                        + " — full body: {}", slug, actual, patched.body());
            }
        } else {
            log.warn("zone {}: could not set org visibility to public ({}): {}",
                    slug, patched.status(), patched.message());
        }
        var teams = forgejo.get(slug, "/orgs/" + slug + "/teams?limit=50");
        if (teams.ok() && teams.body() != null && teams.body().isArray()) {
            for (JsonNode t : teams.body()) {
                if (OWNERS_TEAM.equalsIgnoreCase(t.path("name").asText())) {
                    return t.path("id").asInt(-1);
                }
            }
        }
        return -1;
    }

    /**
     * Repos created before org support existed (or by any direct API use)
     * still belong to the bot service account. Transfer them into the org —
     * the bot owns the org it created, so Forgejo transfers immediately, no
     * acceptance step. The bot has no password anyone knows (by design), so
     * this is the only way those repos become visible to the team without an
     * operator doing it by hand in Forgejo.
     */
    private void migrateBotRepos(String slug) {
        String bot = zones.secret(slug, "forgejo_username");
        if (bot.isBlank()) {
            return;
        }
        var res = forgejo.get(slug, "/users/" + bot + "/repos?limit=50");
        if (!res.ok() || res.body() == null || !res.body().isArray()) {
            return;
        }
        for (JsonNode r : res.body()) {
            String name = r.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            var transfer = forgejo.post(slug, "/repos/" + bot + "/" + name + "/transfer",
                    Map.of("new_owner", slug));
            if (transfer.ok()) {
                log.info("zone {}: moved repo {} from {} to org {}", slug, name, bot, slug);
            }
        }
    }

    /**
     * No SSO in this demo, so a private repo just hides work from the rest of
     * the team for no benefit — force every repo in the org public, including
     * ones created some other way (self-heals like the org visibility above).
     */
    private void unprivateRepos(String slug, JsonNode repoList) {
        if (repoList == null || !repoList.isArray()) {
            return;
        }
        for (JsonNode r : repoList) {
            if (r.path("private").asBoolean(false)) {
                String name = r.path("name").asText("");
                var patched = forgejo.patch(slug, "/repos/" + slug + "/" + name, Map.of("private", false));
                if (patched.ok()) {
                    log.info("zone {}: repo {} was private, made public", slug, name);
                } else {
                    log.warn("zone {}: could not make repo {} public ({}): {}",
                            slug, name, patched.status(), patched.message());
                }
            }
        }
    }

    // --- Forgejo repos -----------------------------------------------------

    public record RepoView(String owner, String name, String fullName, String htmlUrl, String cloneUrl, String version) {}

    /** Every repo in the zone's org — all repos are org-owned, so every zone user can see them. */
    public List<RepoView> repos(String slug) {
        ensureOrg(slug);
        migrateBotRepos(slug);
        var res = forgejo.get(slug, "/orgs/" + slug + "/repos?limit=50");
        unprivateRepos(slug, res.body());
        List<RepoView> out = new ArrayList<>();
        if (res.ok() && res.body() != null && res.body().isArray()) {
            for (JsonNode r : res.body()) {
                String owner = slug;
                String name = r.path("name").asText("");
                int[] v = ReleaseService.latestSemver(
                        forgejo.get(slug, "/repos/%s/%s/tags?limit=50".formatted(owner, name)).body());
                String version = (v[0] == 0 && v[1] == 0 && v[2] == 0)
                        ? "no releases yet" : "v%d.%d.%d".formatted(v[0], v[1], v[2]);
                protectMainBranch(slug, name); // self-heal: catches a repo that predates this protection
                out.add(new RepoView(owner, name, r.path("full_name").asText(""),
                        r.path("html_url").asText(""), r.path("clone_url").asText(""), version));
            }
        }
        return out;
    }

    // --- issues / branches / pull requests, as the calling user's own PAT ---
    //
    // Every action here is attributed to the real person, not ignition-bot —
    // it authenticates with the caller's own personal access token (minted
    // in ensureGitAccess, decrypted with a key derived from their own
    // userId). null/blank if they have no PAT on file yet (shouldn't happen
    // for an existing member — the team console page self-heals this on
    // every load — but a stale/undecryptable row is treated as absent, same
    // as everywhere else this cipher is used).

    /** The calling user's own PAT for this team, or {@code null} if they have none yet. */
    public String myPat(String slug, String email, java.util.UUID userId) {
        String username = gitUsername(slug, email);
        String pat = zones.userSecret(slug, "git_pat_" + username, userId);
        return pat.isBlank() ? null : pat;
    }

    /**
     * Every branch here traces back to an issue — its name is always derived
     * the same deterministic way, so nothing about the branch/PR/merge chain
     * for an issue ever needs to be stored; it's re-derived from the issue
     * number and title every time.
     */
    private static String issueBranch(int number, String title) {
        return number + "-" + TeamNameSuggester.slugify(title);
    }

    public record IssueView(int number, String title, String htmlUrl, String branchName) {}

    /**
     * Open issues on a repo — not pull requests, even though Forgejo shares
     * the tracker between them (a PR is stored as an issue with extra data,
     * and shows up in this same endpoint's results). Seen live: a PR opened
     * for issue #1 showed up as a second row, issue #2, with the PR's own
     * title — meaning the previous filter, {@code type=issue} (singular),
     * wasn't a value Forgejo actually recognized and was silently ignored,
     * so every PR came back indistinguishable from a plain issue.
     * {@code type=issues} (plural) is my best correction from memory of the
     * API shape, not verified against a live swagger doc — so the
     * {@code pull_request} check below filters again client-side regardless,
     * since every issue object reliably carries that field (present only on
     * a PR) and one query param already turned out to be wrong once.
     */
    public List<IssueView> issues(String slug, String repo) {
        var res = forgejo.get(slug, "/repos/%s/%s/issues?type=issues&state=open&limit=50".formatted(slug, repo));
        List<IssueView> out = new ArrayList<>();
        if (res.ok() && res.body() != null && res.body().isArray()) {
            for (JsonNode i : res.body()) {
                if (i.hasNonNull("pull_request")) {
                    continue; // a PR, not a plain issue — see above
                }
                int number = i.path("number").asInt();
                String title = i.path("title").asText("");
                out.add(new IssueView(number, title, i.path("html_url").asText(""), issueBranch(number, title)));
            }
        }
        return out;
    }

    /**
     * Opening an issue always creates its branch too — {@code <number>-<slugified-title>}
     * off {@code main} — there's deliberately no other way to create a branch
     * here, so every branch traces back to the issue that justified it.
     */
    public record IssueOpenResult(boolean ok, int number, String branchName, String message) {}

    public IssueOpenResult createIssue(String slug, String repo, String email, java.util.UUID userId,
                                       String title, String body) {
        var res = forgejo.postAsUser(slug, "/repos/%s/%s/issues".formatted(slug, repo),
                Map.of("title", title, "body", body == null ? "" : body), myPat(slug, email, userId));
        if (!res.ok()) {
            return new IssueOpenResult(false, 0, null, res.message());
        }
        int number = res.body().path("number").asInt();
        String branchName = issueBranch(number, title);
        var branchRes = createBranch(slug, repo, email, userId, branchName, "main");
        if (!branchRes.ok()) {
            log.warn("zone {}: issue #{} opened on {}/{} but branch {} failed: {}",
                    slug, number, slug, repo, branchName, branchRes.message());
            return new IssueOpenResult(true, number, null,
                    "issue opened, but branch creation failed: " + branchRes.message());
        }
        return new IssueOpenResult(true, number, branchName, "");
    }

    /** Not exposed on its own — only {@link #createIssue} calls this, so every branch traces back to an issue. */
    private ForgejoClient.Response createBranch(String slug, String repo, String email, java.util.UUID userId,
                                                String newBranchName, String fromBranch) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("new_branch_name", newBranchName);
        if (fromBranch != null && !fromBranch.isBlank()) {
            body.put("old_branch_name", fromBranch);
        }
        return forgejo.postAsUser(slug, "/repos/%s/%s/branches".formatted(slug, repo), body, myPat(slug, email, userId));
    }

    public record PullView(int number, String title, String head, String base, boolean mergeable, String htmlUrl) {}

    public List<PullView> pulls(String slug, String repo) {
        var res = forgejo.get(slug, "/repos/%s/%s/pulls?state=open&limit=50".formatted(slug, repo));
        List<PullView> out = new ArrayList<>();
        if (res.ok() && res.body() != null && res.body().isArray()) {
            for (JsonNode p : res.body()) {
                int number = p.path("number").asInt();
                String head = p.path("head").path("ref").asText("");
                String base = p.path("base").path("ref").asText("");
                // The list endpoint's own `mergeable` field isn't live —
                // reported enabled for a PR that Forgejo's own UI shows only
                // "Close" for, confirmed live. Fetch it per PR from the
                // single-PR endpoint instead, which actually computes it fresh.
                boolean noConflicts = forgejo.get(slug, "/repos/%s/%s/pulls/%d".formatted(slug, repo, number))
                        .body().path("mergeable").asBoolean(false);
                // Still not enough on its own, though: "mergeable" means "no
                // conflicts", not "there's something to merge" — a branch
                // identical to base trivially has no conflicts, so it still
                // reported true for exactly that case. hasDiff() below is a
                // second, independent check for an actual diff between the two.
                boolean mergeable = noConflicts && hasDiff(slug, repo, base, head);
                out.add(new PullView(number, p.path("title").asText(""), head, base, mergeable,
                        p.path("html_url").asText("")));
            }
        }
        return out;
    }

    /**
     * Whether {@code head} actually differs from {@code base} — via
     * Forgejo/Gitea's compare endpoint. {@code commits} as an array on the
     * response is my best recollection of that endpoint's shape, not
     * verified against a live instance; if this misbehaves (always true, or
     * always false), that field is the first thing to check. Fails open
     * (returns true) on anything unexpected, so a shape mismatch here can't
     * make an actually-mergeable PR look permanently blocked — worst case is
     * back to the original "no conflicts" check alone.
     */
    private boolean hasDiff(String slug, String repo, String base, String head) {
        var res = forgejo.get(slug, "/repos/%s/%s/compare/%s...%s".formatted(slug, repo, base, head));
        JsonNode commits = res.body() == null ? null : res.body().path("commits");
        return commits == null || !commits.isArray() || !commits.isEmpty();
    }

    /**
     * Opens a PR for an issue's branch into {@code main} — the only target,
     * always. {@code Closes #<n>} in the body is Forgejo/Gitea's own
     * close-on-merge keyword, so merging the PR closes the issue too, with
     * no separate step.
     */
    public ForgejoClient.Response openPrForIssue(String slug, String repo, String email, java.util.UUID userId,
                                                 int issueNumber, String issueTitle) {
        return createPullRequest(slug, repo, email, userId, issueTitle,
                issueBranch(issueNumber, issueTitle), "main", "Closes #" + issueNumber);
    }

    /** Not exposed on its own — only {@link #openPrForIssue} calls this. */
    private ForgejoClient.Response createPullRequest(String slug, String repo, String email, java.util.UUID userId,
                                                      String title, String head, String base, String body) {
        return forgejo.postAsUser(slug, "/repos/%s/%s/pulls".formatted(slug, repo),
                Map.of("title", title, "head", head, "base", base == null || base.isBlank() ? "main" : base,
                        "body", body == null ? "" : body),
                myPat(slug, email, userId));
    }

    /** Merges the open PR for an issue's branch — found by matching {@code pulls()} on the derived branch name. */
    public ForgejoClient.Response mergePrForIssue(String slug, String repo, String email, java.util.UUID userId,
                                                  int issueNumber, String issueTitle) {
        String branch = issueBranch(issueNumber, issueTitle);
        return pulls(slug, repo).stream()
                .filter(p -> p.head().equals(branch))
                .findFirst()
                .map(p -> mergePullRequest(slug, repo, email, userId, p.number()))
                .orElseThrow(() -> new IllegalArgumentException("no open PR for issue #" + issueNumber + " yet"));
    }

    /** Not exposed on its own — only {@link #mergePrForIssue} calls this. */
    private ForgejoClient.Response mergePullRequest(String slug, String repo, String email, java.util.UUID userId,
                                                    int index) {
        return forgejo.postAsUser(slug, "/repos/%s/%s/pulls/%d/merge".formatted(slug, repo, index),
                Map.of("Do", "merge"), myPat(slug, email, userId));
    }

    /**
     * Closes the open PR for an issue's branch without merging it — the
     * escape hatch for exactly the case Forgejo's own UI shows only "Close"
     * for (a branch with nothing to merge): rather than a merge attempt that
     * can only ever 405, just close it. Found the same way merging finds it —
     * by matching the derived branch name, not a stored PR number.
     *
     * <p>Deliberately terminal, not a way to retry: also closes the issue
     * itself and deletes the branch, so this issue drops out of the open
     * list and there's nothing left to ever open another PR against — no
     * half-abandoned branch sitting around, no way back into a PR that only
     * ever 405'd. If the work still needs doing, that's a new issue.
     */
    public ForgejoClient.Response closePrForIssue(String slug, String repo, String email, java.util.UUID userId,
                                                  int issueNumber, String issueTitle) {
        String branch = issueBranch(issueNumber, issueTitle);
        String pat = myPat(slug, email, userId);
        var pr = pulls(slug, repo).stream()
                .filter(p -> p.head().equals(branch))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no open PR for issue #" + issueNumber + " yet"));
        var closeRes = forgejo.patchAsUser(slug, "/repos/%s/%s/pulls/%d".formatted(slug, repo, pr.number()),
                Map.of("state", "closed"), pat);
        if (!closeRes.ok()) {
            return closeRes;
        }
        var branchRes = forgejo.deleteAsUser(slug, "/repos/%s/%s/branches/%s".formatted(slug, repo, branch), pat);
        if (!branchRes.ok()) {
            log.warn("zone {}: PR closed for issue #{} but deleting branch {} failed ({}): {}",
                    slug, issueNumber, branch, branchRes.status(), branchRes.message());
        }
        var issueRes = forgejo.patchAsUser(slug, "/repos/%s/%s/issues/%d".formatted(slug, repo, issueNumber),
                Map.of("state", "closed"), pat);
        if (!issueRes.ok()) {
            log.warn("zone {}: PR closed for issue #{} but closing the issue itself failed ({}): {}",
                    slug, issueNumber, issueRes.status(), issueRes.message());
        }
        return closeRes;
    }

    /**
     * New repos always belong to the zone's org, not the bot user, and
     * are always public — this demo has no SSO, and a private repo/org just
     * hides work from the rest of the team for no benefit.
     */
    /**
     * Every app listens on this port inside its container — Traefik routes
     * to it directly over the docker network (no host port is ever
     * published, so there's nothing to "expose"). Not user-configurable:
     * one fewer knob that has to agree with the Dockerfile's {@code EXPOSE}.
     * The container also gets it as a {@code PORT} env var, so an app can
     * just honor {@code $PORT} instead of hardcoding it.
     */
    public static final int APP_PORT = 8080;

    /**
     * "Create an app" — an app <em>is</em> its repo. Creates the repo in the
     * zone's org (always public: no SSO, so a private repo just hides work
     * from teammates), then seeds it with a starter {@code Dockerfile} +
     * page and the deploy workflow, and sets the repo variables/secrets that
     * workflow needs — so the team can clone, push, and hit Release with
     * nothing to configure by hand.
     */
    public ForgejoClient.Response createApp(String slug, String name) {
        ensureOrg(slug);
        ForgejoClient.Response repo = forgejo.post(slug, "/orgs/" + slug + "/repos", Map.of(
                "name", name, "private", false, "auto_init", true));
        if (!repo.ok()) {
            return repo;
        }
        Zone zone = zones.find(slug).orElseThrow(() -> new IllegalStateException("no such zone: " + slug));

        putFile(slug, name, ".forgejo/workflows/deploy.yml", scaffold("deploy.yml"),
                "ignition: add the deploy workflow");
        putFile(slug, name, "Dockerfile", scaffold("Dockerfile"), "ignition: starter Dockerfile");
        putFile(slug, name, "nginx.conf", scaffold("nginx.conf"), "ignition: starter nginx config");
        putFile(slug, name, "index.html", scaffold("index.html"), "ignition: starter page");

        setVar(slug, name, "REGISTRY", zone.gitHost());
        setVar(slug, name, "CONTROL_URL", props.getPublicUrl().replaceAll("/+$", ""));
        setVar(slug, name, "APP_NAME", name);
        setVar(slug, name, "APP_PORT", Integer.toString(APP_PORT));
        setSecret(slug, name, "DEPLOY_TOKEN", zones.secret(slug, "deploy-token"));

        // After seeding, not before — the scaffold commits above go straight
        // to main themselves, so protecting it first would just block them.
        protectMainBranch(slug, name);

        return repo;
    }

    /**
     * Direct pushes to {@code main} are blocked from here on — matches the
     * issue→branch→PR→merge flow the team console drives everything through
     * ({@link #createIssue}, {@link #openPrForIssue}, {@link #mergePrForIssue}).
     * A PR's merge still lands on {@code main} normally; only a raw
     * {@code git push} to it is refused.
     *
     * <p>{@code branch_name} is Forgejo/Gitea's older, more broadly-compatible
     * field name for this endpoint — some versions have since moved to a
     * pattern-based {@code rule_name} instead. Written from memory of the
     * API shape, not verified against a live instance; if this 422s, that
     * field name is the first thing to check.
     */
    private void protectMainBranch(String slug, String repo) {
        if (forgejo.get(slug, "/repos/" + slug + "/" + repo + "/branch_protections/main").ok()) {
            return; // already protected — cheap to check, idempotent either way
        }
        var res = forgejo.post(slug, "/repos/" + slug + "/" + repo + "/branch_protections", Map.of(
                "branch_name", "main",
                "enable_push", false,
                "enable_merge_whitelist", false));
        if (res.ok()) {
            log.info("zone {}: main protected on {} (no direct push — PRs only)", slug, repo);
        } else {
            log.warn("zone {}: protecting main on {} failed ({}): {}", slug, repo, res.status(), res.message());
        }
    }

    private void putFile(String slug, String repo, String path, String content, String message) {
        var res = forgejo.post(slug, "/repos/" + slug + "/" + repo + "/contents/" + path, Map.of(
                "content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)),
                "message", message,
                "branch", "main"));
        if (!res.ok()) {
            log.warn("zone {}: seeding {} in {} failed ({}): {}", slug, path, repo, res.status(), res.message());
        }
    }

    private void setVar(String slug, String repo, String key, String value) {
        var res = forgejo.put(slug, "/repos/" + slug + "/" + repo + "/actions/variables/" + key,
                Map.of("value", value));
        if (!res.ok()) {
            log.warn("zone {}: setting variable {} on {} failed ({}): {}",
                    slug, key, repo, res.status(), res.message());
        }
    }

    private void setSecret(String slug, String repo, String key, String value) {
        var res = forgejo.put(slug, "/repos/" + slug + "/" + repo + "/actions/secrets/" + key,
                Map.of("data", value));
        if (!res.ok()) {
            log.warn("zone {}: setting secret {} on {} failed ({}): {}",
                    slug, key, repo, res.status(), res.message());
        }
    }

    private static String scaffold(String name) {
        try (var in = ZoneService.class.getResourceAsStream("/scaffold/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing scaffold resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ReleaseService.Result release(String slug, String owner, String repo, String kind) {
        return releases.cut(slug, owner, repo, kind);
    }

    // --- runner / stack (project-scoped compose) --------------------------

    public boolean restartRunner(String slug) {
        return compose(slug, "restart", "runner").ok();
    }

    public String stack(String slug) {
        DockerCli.Result r = compose(slug, "ps", "--format", "{{.Service}}={{.State}}");
        return r.ok() && !r.stdout().isBlank() ? r.stdout().strip().replace("\n", " ") : "—";
    }

    private DockerCli.Result compose(String slug, String... args) {
        Zone zone = zones.find(slug).orElseThrow(() -> new IllegalArgumentException("no such zone: " + slug));
        Path compose = render.zoneCompose(zone);
        return docker.compose(dockerHost(zone), "zone-" + slug, compose.toString(), args);
    }

    // --- recreate on start (picks up a compose/label/image change) --------

    /**
     * Recreates every zone's containers from the current template — a
     * plain {@code down} + {@code up -d}, deliberately never {@code -v} — so
     * a change to {@code zone-compose.yml.tmpl} (a new Traefik label, an
     * image bump) reaches zones that are already running. Volumes
     * (forgejo-data, dind-data, runner-data) are untouched either way, so no
     * zone data — repos, Forgejo's own host keys, the DB-stored tokens/PATs —
     * is at risk. Off by default ({@link IgnitionProperties#isRecreateZonesOnStart()});
     * {@code update-and-run.sh} turns it on for its own restart. Best-effort:
     * one zone failing doesn't stop the rest.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recreateZonesOnStart() {
        if (!props.isRecreateZonesOnStart()) {
            return;
        }
        List<Zone> all = zones.findAll();
        log.info("recreating {} zone stack(s) on start (IGN_RECREATE_ZONES_ON_START=true)", all.size());
        for (Zone zone : all) {
            String slug = zone.slug();
            try {
                Path compose = render.zoneCompose(zone);
                String host = dockerHost(zone);
                docker.compose(host, "zone-" + slug, compose.toString(), "down", "--remove-orphans");
                DockerCli.Result up = docker.compose(host, "zone-" + slug, compose.toString(), "up", "-d");
                if (up.ok()) {
                    log.info("zone {}: stack recreated", slug);
                } else {
                    log.warn("zone {}: recreate failed: {}", slug, up.stderr().isBlank() ? up.stdout() : up.stderr());
                }
            } catch (RuntimeException e) {
                log.warn("zone {}: recreate failed", slug, e);
            }
        }
    }
}
