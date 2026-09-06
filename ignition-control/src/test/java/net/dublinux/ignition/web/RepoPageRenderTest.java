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
        ctx.setVariable("repoInfo", new ZoneService.RepoView("acme", "cards", "acme/cards",
                "https://git.acme.example/acme/cards", "https://git.acme.example/acme/cards.git",
                "a card app", "v1.1.0"));
        ctx.setVariable("issueRows", List.of());
        ctx.setVariable("unreleasedIssues", List.of());
        ctx.setVariable("currentUserId", null);
        ctx.setVariable("myGitUsername", null);
        ctx.setVariable("myGitPassword", null);
        ctx.setVariable("myGitPat", null);
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
}
