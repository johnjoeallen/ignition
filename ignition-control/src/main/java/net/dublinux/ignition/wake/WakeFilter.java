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
        if (state == WakeService.State.SLEEPING && isBrowserRequest(req, path)) {
            wake.wake(t);
            state = WakeService.State.WAKING;
        }

        res.setHeader(HEADER, state.name().toLowerCase(Locale.ROOT));
        res.setHeader("Cache-Control", "no-store");
        boolean wantsHtml = req.getHeader("Accept") != null && req.getHeader("Accept").contains("text/html");
        switch (state) {
            case UNKNOWN -> {
                if (wantsHtml) {
                    page(res, HttpServletResponse.SC_NOT_FOUND, "Nothing here",
                            "There is no app at <b>%s</b>.".formatted(host), false);
                } else {
                    json(res, HttpServletResponse.SC_NOT_FOUND, "unknown", "There is no app at " + host + ".");
                }
            }
            case TEAM_STOPPED -> {
                if (wantsHtml) {
                    page(res, HttpServletResponse.SC_SERVICE_UNAVAILABLE, t.name() + " is stopped",
                            "<b>%s</b> was stopped by its team. A team admin can start it again from the team console."
                                    .formatted(t.name()), false);
                } else {
                    json(res, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "stopped",
                            t.name() + " was stopped by its team.");
                }
            }
            case WAKING -> {
                res.setHeader("Retry-After", "5");
                if (wantsHtml) {
                    page(res, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Starting " + t.name(),
                            "<b>%s</b> was stopped after a period of inactivity. It's starting up now, and this page will open it as soon as it's ready."
                                    .formatted(t.name()), true);
                } else {
                    json(res, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "starting",
                            t.name() + " is starting — retry in a few seconds.");
                }
            }
            case SLEEPING -> { // not a browser request: don't start it, just say so
                res.setHeader("Retry-After", "30");
                json(res, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "asleep",
                        t.name() + " is asleep. Open it in a browser to start it.");
            }
        }
    }

    /** A machine-readable 503/404 for requests that aren't asking for a page (fetch/XHR, API clients). */
    private static void json(HttpServletResponse res, int status, String state, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"status\":\"%s\",\"message\":\"%s\"}"
                .formatted(state, message.replace("\\", "\\\\").replace("\"", "\\\"")));
    }

    /**
     * Would a real browser, with a person behind it, have sent this? Then a stopped
     * app is worth starting. A navigation asks for {@code text/html}; and every
     * fetch/XHR a browser makes (including from a page it served out of its own
     * cache while the app was stopped) carries {@code Sec-Fetch-*} headers, which
     * curl, monitors and webhooks don't send. Probes, crawlers and the
     * robots/favicon fetches never wake anything.
     */
    static boolean isBrowserRequest(HttpServletRequest req, String path) {
        String method = req.getMethod();
        if ("OPTIONS".equals(method) || "/robots.txt".equals(path) || "/favicon.ico".equals(path)) {
            return false;
        }
        String ua = req.getHeader("User-Agent");
        if (ua == null) {
            return false;
        }
        String u = ua.toLowerCase(Locale.ROOT);
        if (u.equals(AppHealthProbe.USER_AGENT)
                || u.contains("bot") || u.contains("crawl") || u.contains("spider") || u.contains("slurp")) {
            return false;
        }
        String accept = req.getHeader("Accept");
        boolean navigation = "GET".equals(method) && accept != null && accept.contains("text/html");
        return navigation || req.getHeader("Sec-Fetch-Site") != null;
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
                      if (++tries > 90) {
                        document.getElementById('msg').textContent =
                          'This is taking longer than expected. Try reloading in a minute.';
                        return;
                      }
                      fetch('/__ign/wake-status', {cache: 'no-store'}).then(function (r) {
                        var s = r.headers.get('X-Ignition-Wake');
                        if (s === 'waking') { poll(); return; }
                        if (!s && r.status >= 500) {
                          // the app's own route is up but it isn't answering yet
                          document.getElementById('msg').textContent = 'Almost there — waiting for the app to answer…';
                          poll();
                          return;
                        }
                        location.reload(); // answering (or asleep again, which retries the wake)
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
                  body { font: 16px/1.5 system-ui, sans-serif; margin: 0; width: 100vw; min-height: 100vh; display: grid;
                         place-items: center; background: #f6f7f9; color: #1c2430; }
                  main { max-width: 32rem; padding: 2rem; text-align: center; }
                  @media (prefers-color-scheme: dark) { body { background: #14181f; color: #e6e9ee; } }
                  .spin { width: 56px; height: 56px; margin: 0 auto 1.5rem; border: 5px solid #8883;
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
