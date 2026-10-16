package net.dublinux.ignition.wake;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.dublinux.ignition.app.AppHealthProbe;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers requests that reach this control plane under an app host
 * ({@code <name>.apps|dev.<slug>.<base>}) — i.e. through the catch-all route
 * Traefik uses when the app's own route is gone because it is stopped. Runs
 * ahead of Spring Security: these visitors have no login.
 *
 * <p>Always a non-2xx status, so the console's health probe (which treats
 * anything under 500 other than 404 as up) reads a stopped app as down, and
 * only a real browser page load starts an app — never a probe, a crawler, or an
 * API client.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class WakeFilter extends OncePerRequestFilter {

    static final String STATUS_PATH = "/__ign/wake-status";
    static final String HEADER = "X-Ignition-Wake";

    private final WakeService wake;

    public WakeFilter(WakeService wake) {
        this.wake = wake;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String host = req.getHeader("Host");
        if (host != null && host.indexOf(':') > 0) {
            host = host.substring(0, host.indexOf(':'));
        }
        Optional<WakeService.Target> target = wake.parse(host);
        if (target.isEmpty()) {
            chain.doFilter(req, res);
            return;
        }
        WakeService.Target t = target.get();
        String path = req.getRequestURI();
        WakeService.State state = wake.state(t);

        if (STATUS_PATH.equals(path)) { // the page's poll: report, never start anything
            res.setHeader(HEADER, state.name().toLowerCase(Locale.ROOT));
            res.setHeader("Cache-Control", "no-store");
            res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        if (state == WakeService.State.SLEEPING && isBrowserPageLoad(req, path)) {
            wake.wake(t);
            state = WakeService.State.WAKING;
        }

        res.setHeader(HEADER, state.name().toLowerCase(Locale.ROOT));
        res.setHeader("Cache-Control", "no-store");
        switch (state) {
            case UNKNOWN -> page(res, HttpServletResponse.SC_NOT_FOUND, "Nothing here",
                    "There is no app at <b>%s</b>.".formatted(host), false);
            case TEAM_STOPPED -> page(res, HttpServletResponse.SC_SERVICE_UNAVAILABLE, t.name() + " is stopped",
                    "<b>%s</b> was stopped by its team. A team admin can start it again from the team console."
                            .formatted(t.name()), false);
            case WAKING -> {
                res.setHeader("Retry-After", "5");
                page(res, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Starting " + t.name(),
                        "<b>%s</b> was asleep after a quiet spell. It's starting now — this page reloads when it's ready."
                                .formatted(t.name()), true);
            }
            case SLEEPING -> { // not a browser page load: don't start it, just say so
                res.setHeader("Retry-After", "30");
                res.setContentType("text/plain;charset=UTF-8");
                res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                res.getWriter().write(t.name() + " is asleep. Open it in a browser to start it.\n");
            }
        }
    }

    private static boolean isBrowserPageLoad(HttpServletRequest req, String path) {
        if (!"GET".equals(req.getMethod()) || "/robots.txt".equals(path) || "/favicon.ico".equals(path)) {
            return false;
        }
        String accept = req.getHeader("Accept");
        String ua = req.getHeader("User-Agent");
        if (accept == null || !accept.contains("text/html") || ua == null) {
            return false;
        }
        String u = ua.toLowerCase(Locale.ROOT);
        return !u.equals(AppHealthProbe.USER_AGENT)
                && !u.contains("bot") && !u.contains("crawl") && !u.contains("spider") && !u.contains("slurp");
    }

    private static void page(HttpServletResponse res, int status, String title, String message, boolean poll)
            throws IOException {
        res.setStatus(status);
        res.setContentType("text/html;charset=UTF-8");
        String script = poll ? """
                <script>
                (function () {
                  var tries = 0;
                  (function poll() {
                    setTimeout(function () {
                      if (++tries > 45) {
                        document.getElementById('msg').textContent =
                          'This is taking longer than expected. Try reloading in a minute.';
                        return;
                      }
                      fetch('/__ign/wake-status', {cache: 'no-store'}).then(function (r) {
                        var s = r.headers.get('X-Ignition-Wake');
                        if (s === 'waking') { poll(); } else { location.reload(); }
                      }).catch(poll);
                    }, 2000);
                  })();
                })();
                </script>
                """ : "";
        String html = """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%1$s</title>
                <style>
                  body { font: 16px/1.5 system-ui, sans-serif; margin: 0; min-height: 100vh; display: grid;
                         place-items: center; background: #f6f7f9; color: #1c2430; }
                  main { max-width: 32rem; padding: 2rem; text-align: center; }
                  @media (prefers-color-scheme: dark) { body { background: #14181f; color: #e6e9ee; } }
                  .spin { width: 28px; height: 28px; margin: 0 auto 1rem; border: 3px solid #8883;
                          border-top-color: #e8590c; border-radius: 50%%; animation: s 1s linear infinite; }
                  @keyframes s { to { transform: rotate(360deg); } }
                </style></head>
                <body><main>
                %3$s
                <h1 style="font-size:1.4rem">%1$s</h1>
                <p id="msg">%2$s</p>
                </main>
                %4$s</body></html>
                """.formatted(title, message, poll ? "<div class=\"spin\"></div>" : "", script);
        res.getOutputStream().write(html.getBytes(StandardCharsets.UTF_8));
    }
}
