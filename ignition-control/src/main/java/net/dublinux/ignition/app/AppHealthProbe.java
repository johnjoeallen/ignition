package net.dublinux.ignition.app;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import net.dublinux.ignition.config.IgnitionProperties;
import net.dublinux.ignition.forgejo.ForgejoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Asks a deployed app over HTTP — through its public URL, so the edge and the
 * node's Traefik route are part of the test — whether it is actually serving.
 * That catches what {@code docker ps} can't: a container that is "Up" but
 * wedged, and an app whose route is gone, where the edge answers 404.
 *
 * <p>The app picks its own check with a {@code ignition.health.path} label on
 * its web service (e.g. {@code /actuator/health}); that path must answer 2xx or
 * 3xx. With no label the probe hits {@code /} and only fails on a connect/TLS
 * error, a 5xx, or a 404 — so an app that legitimately 401s or redirects its
 * root still counts as up, while the edge's "no such route" 404 does not.
 * Redirects are not followed.
 */
@Component
public class AppHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(AppHealthProbe.class);

    /** Label on the web service that names the health path. */
    public static final String LABEL = "ignition.health.path";

    /** Starts with {@code /}; path/query characters only — never a host, userinfo or whitespace. */
    private static final Pattern PATH = Pattern.compile("^/[A-Za-z0-9._~!$&'()*+,;=:@%/?-]*$");

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final long TTL_MS = 5_000;

    private record Cached(long at, boolean up) {}

    private final HttpClient http;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public AppHealthProbe(IgnitionProperties props) {
        this.http = ForgejoClient.buildClient(props.isInsecureTls());
    }

    /**
     * @param appUrl     the app's public URL ({@code https://…/})
     * @param healthPath the label value, or null/blank for the default check
     */
    public boolean up(String appUrl, String healthPath) {
        boolean explicit = healthPath != null && PATH.matcher(healthPath.strip()).matches();
        String path = explicit ? healthPath.strip() : "/";
        String url = appUrl.replaceAll("/+$", "") + path;

        Cached c = cache.get(url);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < TTL_MS) {
            return c.up();
        }
        boolean up;
        try {
            HttpResponse<Void> r = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            int code = r.statusCode();
            up = explicit ? code >= 200 && code < 400 : code < 500 && code != 404;
            if (!up) {
                log.info("health {} -> {}", url, code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return c != null && c.up();
        } catch (Exception e) {
            log.info("health {} -> {}", url, e.toString());
            up = false;
        }
        cache.put(url, new Cached(now, up));
        return up;
    }
}
