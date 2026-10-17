package net.dublinux.ignition.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import net.dublinux.ignition.release.ReleaseService;
import net.dublinux.ignition.zone.ZoneService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders {@code repo.html} (and the fragments it pulls in) with a plain
 * Thymeleaf engine, so the unreleased-work strip's conditionals are exercised
 * without booting the whole app. Not a pixel test — it just has to not throw
 * and to put the right words on the page for each branch.
 */
class RepoPageRenderTest {

    private static SpringTemplateEngine engine() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static WebContext baseContext() {
        var servletContext = new MockServletContext();
        var app = JakartaServletWebApplication.buildApplication(servletContext);
        var exchange = app.buildExchange(new MockHttpServletRequest(servletContext),
                new MockHttpServletResponse());
        var ctx = new WebContext(exchange);
        ctx.setVariable("param", Map.of());
        ctx.setVariable("isPlatformAdmin", false);
        ctx.setVariable("principal", "alice@example.com");
        ctx.setVariable("buildVersion", "test");
        ctx.setVariable("buildCommit", "");
        ctx.setVariable("zoneSlug", "acme");
        ctx.setVariable("repoName", "cards");
        ctx.setVariable("baseDomain", "ignition.example");
        ctx.setVariable("devUrl", null);
        ctx.setVariable("devDeployedAt", null);
        ctx.setVariable("devRunning", false);
        ctx.setVariable("previews", List.of());
        ctx.setVariable("repoInfo", new ZoneService.RepoView("acme", "cards", "acme/cards",
                "https://git.acme.example/acme/cards", "https://git.acme.example/acme/cards.git",
                "a card app", "v1.1.0"));
        ctx.setVariable("issueRows", List.of());
        ctx.setVariable("unreleasedIssues", List.of());
        ctx.setVariable("currentUserId", null);
        ctx.setVariable("myGitUsername", null);
        ctx.setVariable("myGitPassword", null);
        ctx.setVariable("myGitPat", null);
        ctx.setVariable("releaseDeployed", false);
        ctx.setVariable("releaseRunning", false);
        ctx.setVariable("releaseUrl", null);
        ctx.setVariable("statusUrl", "/teams/acme/apps/status");
        ctx.setVariable("idleTtl", "");
        ctx.setVariable("canManageApp", true);
        ctx.setVariable("secretSections", List.of(
                new ZoneConsoleController.SecretSection("release", "Release", "hint", "current", List.of()),
                new ZoneConsoleController.SecretSection("dev", "Dev (main)", "hint", "current", List.of()),
                new ZoneConsoleController.SecretSection("preview", "PR previews", "hint", "none", List.of())));
        ctx.setVariable("canManageSecrets", true);
        return ctx;
    }

    @Test
    void rendersWithUnreleasedWork() {
        var ctx = baseContext();
        ctx.setVariable("pending", new ReleaseService.Pending(true, "v1.1.0", Instant.parse("2020-01-01T00:00:00Z"),
                2, List.of(new ReleaseService.PendingPr(3, "Get US Mastercard cards")), "minor"));
        ctx.setVariable("unreleasedIssues", List.of(
                new ZoneService.IssueView(3, "Get US Mastercard cards",
                        "https://git.acme.example/acme/cards/issues/3", "3-get-us-mastercard-cards")));

        String html = engine().process("repo", ctx);

        assertThat(html).contains("commits");
        assertThat(html).contains("since");
        assertThat(html).contains("#3 Get US Mastercard cards");
        assertThat(html).contains("Closed, not released yet");
        assertThat(html).contains("suggested from the commit messages");
        // the suggested bump (minor) button carries the highlight class
        assertThat(html).containsPattern("value=\"minor\"[^>]*btn-accent");
        assertThat(html).doesNotContain("nothing to release");
    }

    @Test
    void rendersWhenUpToDate() {
        var ctx = baseContext();
        ctx.setVariable("pending", new ReleaseService.Pending(true, "v1.1.0", Instant.now(),
                0, List.of(), "patch"));

        String html = engine().process("repo", ctx);

        assertThat(html).contains("nothing to release");
        assertThat(html).contains("confirm(");                     // empty-state guard on the release form
        assertThat(html).doesNotContainPattern("value=\"[a-z]+\"[^>]*btn-accent");  // no bump highlighted
        assertThat(html).doesNotContain("Closed, not released yet");
    }

    @Test
    void rendersDevBlock() {
        var ctx = baseContext();
        ctx.setVariable("pending", new ReleaseService.Pending(true, "v1.1.0", Instant.now(),
                0, List.of(), "patch"));

        // no dev deployment yet
        String html = engine().process("repo", ctx);
        assertThat(html).contains("Deploy from main");
        assertThat(html).contains("cards.dev.acme.ignition.example");
        assertThat(html).doesNotContain(">stop<");

        // with a live dev deployment
        ctx.setVariable("devUrl", "https://cards.dev.acme.ignition.example/");
        ctx.setVariable("devDeployedAt", "6 Sep 09:30 UTC");
        ctx.setVariable("devRunning", true);
        html = engine().process("repo", ctx);
        assertThat(html).contains("https://cards.dev.acme.ignition.example/");
        assertThat(html).contains("6 Sep 09:30 UTC");
        assertThat(html).contains(">stop<");
    }

    @Test
    void secretsAreListedByNameAndNeverShowTheirValue() {
        var ctx = baseContext();
        var secret = new net.dublinux.ignition.app.AppSecret("acme", "cards",
                net.dublinux.ignition.app.SecretScope.DEV, "STRIPE_KEY",
                "CIPHERTEXT-SHOULD-NEVER-RENDER", "alice@example.com");
        ctx.setVariable("secretSections", List.of(
                new ZoneConsoleController.SecretSection("release", "Release", "hint", "current", List.of()),
                new ZoneConsoleController.SecretSection("dev", "Dev (main)", "hint", "pending", List.of(secret)),
                new ZoneConsoleController.SecretSection("preview", "PR previews", "hint", "none", List.of())));
        String html = engine().process("repo", ctx);
        assertThat(html).contains("STRIPE_KEY").contains("Save secret")
                .contains("Release").contains("Dev (main)").contains("PR previews");
        assertThat(html).doesNotContain("CIPHERTEXT-SHOULD-NEVER-RENDER");
        // dev has an unapplied change -> its restart button is live; release is current -> disabled
        assertThat(html).contains("Secrets changed since the last deploy")
                .contains("Up to date with the running deployment");
        assertThat(html.split("Restart with new secrets</button>").length - 1).isEqualTo(2);
        String release = html.substring(html.indexOf("data-secrets=\"release\""), html.indexOf("data-secrets=\"dev\""));
        String dev = html.substring(html.indexOf("data-secrets=\"dev\""), html.indexOf("data-secrets=\"preview\""));
        assertThat(release).contains("disabled");
        assertThat(dev).doesNotContain("disabled");

        ctx.setVariable("canManageSecrets", false);
        html = engine().process("repo", ctx);
        assertThat(html).contains("STRIPE_KEY").doesNotContain("Save secret")
                .contains("Only a team admin can change secrets");
    }

    @Test
    void rendersPrPreviews() {
        var ctx = baseContext();
        ctx.setVariable("pending", new ReleaseService.Pending(true, "v1.1.0", Instant.now(), 0, List.of(), "patch"));
        ctx.setVariable("previews", List.of(
                Map.of("name", "cards-pr-3", "url", "https://cards-pr-3.apps.acme.ignition.example/",
                        "number", "3", "running", true),
                Map.of("name", "cards-pr-7", "url", "https://cards-pr-7.apps.acme.ignition.example/",
                        "number", "7", "running", false)));

        String html = engine().process("repo", ctx);
        assertThat(html).contains("Deployments").contains("PR #3").contains("PR #7");
        assertThat(html).contains("cards-pr-3");
        assertThat(html).contains("https://cards-pr-7.apps.acme.ignition.example/");
        // no previews -> the hint about the label instead
        ctx.setVariable("previews", List.of());
        assertThat(engine().process("repo", ctx)).contains("deploy-preview");
    }

    @Test
    void everyDeploymentGetsALaunchLinkEvenWhenItIsNotAnswering() {
        var ctx = baseContext();
        ctx.setVariable("releaseDeployed", true);
        ctx.setVariable("releaseRunning", false);
        ctx.setVariable("releaseUrl", "https://cards.apps.acme.ignition.example/");
        ctx.setVariable("devUrl", "https://cards.dev.acme.ignition.example/");
        ctx.setVariable("devRunning", false);
        ctx.setVariable("previews", List.of(Map.of("name", "cards-pr-7",
                "url", "https://cards-pr-7.apps.acme.ignition.example/", "number", "7", "running", false)));
        String html = engine().process("repo", ctx);
        assertThat(html).contains("href=\"https://cards.apps.acme.ignition.example/\"")
                .contains("href=\"https://cards.dev.acme.ignition.example/\"")
                .contains("href=\"https://cards-pr-7.apps.acme.ignition.example/\"");
        assertThat(html.split("launch</a>").length - 1).isEqualTo(3);
    }

    @Test
    void deleteControlsAreOnlyForTeamAndPlatformAdmins() {
        var ctx = baseContext();
        ctx.setVariable("devUrl", "https://cards.dev.acme.ignition.example/");
        ctx.setVariable("devRunning", true);
        ctx.setVariable("previews", List.of(Map.of("name", "cards-pr-7",
                "url", "https://cards-pr-7.apps.acme.ignition.example/", "number", "7", "running", true)));

        ctx.setVariable("canManageApp", true);
        String html = engine().process("repo", ctx);
        assertThat(html).contains("Delete app").contains("dev/delete").contains("previews/delete");

        ctx.setVariable("canManageApp", false);
        html = engine().process("repo", ctx);
        assertThat(html).doesNotContain("Delete app").doesNotContain("dev/delete").doesNotContain("previews/delete");
    }

    @Test
    void secretsCanBeSetBeforeAnythingIsDeployed() {
        var ctx = baseContext();
        ctx.setVariable("secretSections", List.of(
                new ZoneConsoleController.SecretSection("release", "Release", "hint", "undeployed", List.of()),
                new ZoneConsoleController.SecretSection("dev", "Dev (main)", "hint", "undeployed", List.of()),
                new ZoneConsoleController.SecretSection("preview", "PR previews", "hint", "undeployed", List.of())));
        String html = engine().process("repo", ctx);
        // the add form is there for every environment, with no restart button to press yet
        assertThat(html.split("Save secret").length - 1).isEqualTo(3);
        assertThat(html).doesNotContain("Restart with new secrets</button>");
        assertThat(html).contains("Nothing deployed yet").contains("No PR previews running");
    }
}
