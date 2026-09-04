package net.dublinux.ignition.forgejo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import net.dublinux.ignition.config.IgnitionProperties;
import net.dublinux.ignition.zone.ZoneRepository;
import org.springframework.stereotype.Component;

/**
 * Per-zone Forgejo REST wrapper. Uses the {@code zoneadmin} token minted at
 * provisioning (the encrypted {@code forgejo_token} / {@code forgejo_url}
 * {@code zone_secret} rows) — the zone admin never sees it.
 */
@Component
public class ForgejoClient {

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

    private Response send(String slug, String method, String path, Map<String, ?> body) {
        // Reach the zone's Forgejo directly over the shared docker network — the
        // public git.<slug>.<domain> name isn't resolvable from in here, and this
        // avoids a hairpin through the edge. Matches container_name in
        // zone-compose.yml.tmpl.
        String base = "http://zone-" + slug + "-forgejo:3000";
        String token = zones.secret(slug, "forgejo_token");
        if (token.isBlank()) {
            return new Response(503, error("zone has no Forgejo admin token yet"));
        }
        try {
            HttpRequest.BodyPublisher pub = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/v1" + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "token " + token)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .method(method, pub)
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            String raw = res.body();
            JsonNode node = (raw == null || raw.isBlank()) ? json.nullNode() : safeParse(raw);
            return new Response(res.statusCode(), node);
        } catch (java.io.IOException | JacksonException e) {
            return new Response(502, error(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(502, error("interrupted"));
        }
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
