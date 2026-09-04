package net.dublinux.ignition.forgejo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import net.dublinux.ignition.config.IgnitionProperties;
import net.dublinux.ignition.zone.ZoneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Per-zone Forgejo REST wrapper. Uses the bot service account's token minted at
 * provisioning (the encrypted {@code forgejo_token} / {@code forgejo_url}
 * {@code zone_secret} rows) — the zone admin never sees it.
 */
@Component
public class ForgejoClient {

    private static final Logger log = LoggerFactory.getLogger(ForgejoClient.class);

    /** {@code (status, body)} — body is a parsed JSON node, or an {@code {"error": …}} node. */
    public record Response(int status, JsonNode body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public String message() {
            if (body != null && body.hasNonNull("message")) {
                return body.get("message").asText();
            }
            if (body != null && body.hasNonNull("error")) {
                return body.get("error").asText();
            }
            return body == null ? "" : body.toString();
        }
    }

    private final ZoneRepository zones;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;

    public ForgejoClient(IgnitionProperties props, ZoneRepository zones) {
        this.zones = zones;
        this.http = buildClient(props.isInsecureTls());
    }

    public Response get(String slug, String path) {
        return send(slug, "GET", path, null);
    }

    public Response post(String slug, String path, Map<String, ?> body) {
        return send(slug, "POST", path, body);
    }

    public Response put(String slug, String path, Map<String, ?> body) {
        return send(slug, "PUT", path, body);
    }

    public Response patch(String slug, String path, Map<String, ?> body) {
        return send(slug, "PATCH", path, body);
    }

    public Response delete(String slug, String path) {
        return send(slug, "DELETE", path, null);
    }

    /**
     * Same as {@link #post}, but authenticated as HTTP Basic (the bot's own
     * username:password) instead of its usual bearer token. Forgejo/Gitea
     * deliberately refuses token auth on the personal-access-token endpoints
     * — create and delete both — since a leaked token minting or revoking
     * more tokens would defeat the point of being able to revoke it. So a
     * {@code sudo=<username>} call to mint or delete *someone else's* token
     * has to go in as Basic even though every other admin call here uses the
     * token. Confirmed against a live instance: token auth on these two gets
     * a flat {@code 401 auth method not allowed}.
     */
    public Response postBasicAuth(String slug, String path, Map<String, ?> body) {
        return sendBasicAuth(slug, "POST", path, body);
    }

    public Response deleteBasicAuth(String slug, String path) {
        return sendBasicAuth(slug, "DELETE", path, null);
    }

    private Response send(String slug, String method, String path, Map<String, ?> body) {
        String token = zones.secret(slug, "forgejo_token");
        if (token.isBlank()) {
            log.warn("[{}] {} {} — no forgejo_token secret yet (zone not fully provisioned?)", slug, method, path);
            return new Response(503, error("zone has no Forgejo admin token yet"));
        }
        return send(slug, method, path, body, "token " + token, "token …" + tail(token));
    }

    private Response sendBasicAuth(String slug, String method, String path, Map<String, ?> body) {
        String user = zones.secret(slug, "forgejo_username");
        String pass = zones.secret(slug, "forgejo_password");
        if (user.isBlank() || pass.isBlank()) {
            log.warn("[{}] {}(basic) {} — no forgejo_username/forgejo_password secret yet", slug, method, path);
            return new Response(503, error("zone has no Forgejo admin credentials yet"));
        }
        String basic = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        return send(slug, method + "(basic)", path, body, "Basic " + basic, "as " + user);
    }

    /**
     * @param authDescription logged in place of the credential itself — either
     *                        {@code "token …<last 4>"} or {@code "as <bot username>"}.
     */
    private Response send(String slug, String method, String path, Map<String, ?> body,
                          String authorizationHeader, String authDescription) {
        // Reach the zone's Forgejo directly over the shared docker network — the
        // public git.<slug>.<domain> name isn't resolvable from in here, and this
        // avoids a hairpin through the edge. Matches container_name in
        // zone-compose.yml.tmpl.
        String base = "http://zone-" + slug + "-forgejo:3000";
        String url = base + "/api/v1" + path;
        log.info("[{}] -> {} {} ({}, body keys {})", slug, method, url,
                authDescription, body == null ? "none" : body.keySet());
        try {
            HttpRequest.BodyPublisher pub = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", authorizationHeader)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .method(method.replace("(basic)", ""), pub)
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            String raw = res.body();
            JsonNode node = (raw == null || raw.isBlank()) ? json.nullNode() : safeParse(raw);
            Response out = new Response(res.statusCode(), node);
            if (out.ok()) {
                log.info("[{}] <- {} {} {}", slug, method, path, res.statusCode());
            } else {
                // Forgejo's own error body — never anything we sent, safe to log in full.
                log.warn("[{}] <- {} {} {} : {}", slug, method, path, res.statusCode(),
                        raw == null ? "" : (raw.length() > 1000 ? raw.substring(0, 1000) + "…" : raw));
            }
            return out;
        } catch (java.io.IOException e) {
            log.warn("[{}] {} {} failed: {}: {}", slug, method, path,
                    e.getClass().getSimpleName(), e.getMessage());
            return new Response(502, error(e.getMessage()));
        } catch (JacksonException e) {
            log.warn("[{}] {} {} — response wasn't valid JSON: {}", slug, method, path, e.getMessage());
            return new Response(502, error(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(502, error("interrupted"));
        }
    }

    private static String tail(String token) {
        return token.length() <= 4 ? "…" : token.substring(token.length() - 4);
    }

    private JsonNode safeParse(String raw) {
        try {
            return json.readTree(raw);
        } catch (JacksonException e) {
            return error(raw.length() > 400 ? raw.substring(0, 400) : raw);
        }
    }

    private JsonNode error(String msg) {
        return json.createObjectNode().put("error", msg);
    }

    private static HttpClient buildClient(boolean insecure) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (insecure) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{new X509TrustManager() {
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }}, new java.security.SecureRandom());
                b.sslContext(ctx);
            } catch (Exception e) {
                throw new IllegalStateException("insecure TLS context", e);
            }
        }
        return b.build();
    }
}
