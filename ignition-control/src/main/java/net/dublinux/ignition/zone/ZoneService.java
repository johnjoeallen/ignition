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
    private static final List<String> PAT_SCOPES = List.of("write:repository", "write:user");

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
     */
    private void ensurePat(String slug, String username, java.util.UUID userId) {
        if (!zones.userSecret(slug, "git_pat_" + username, userId).isBlank()) {
            return;
        }
        if (zones.hasSecret(slug, "git_pat_" + username)) {
            forgejo.deleteBasicAuth(slug, "/users/" + username + "/tokens/ignition?sudo=" + username);
        }
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

    public record RepoView(String owner, String name, String fullName, String htmlUrl, String version) {}

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
                out.add(new RepoView(owner, name, r.path("full_name").asText(""),
                        r.path("html_url").asText(""), version));
            }
        }
        return out;
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

        return repo;
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
}
