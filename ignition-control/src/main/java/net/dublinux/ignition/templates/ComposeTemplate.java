package net.dublinux.ignition.templates;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Renders the bundled compose templates. Substitution is <b>explicit</b> — only
 * {@code ${KEY}} for a {@code KEY} present in the supplied map is replaced;
 * anything else (a stray {@code $x}, a compose var we don't own) is left
 * verbatim. Same discipline as {@code envsubst "$VARLIST"} in the shell
 * scripts, so a literal {@code $} in a compose file is never clobbered.
 */
@Component
public class ComposeTemplate {

    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private final String appTemplate = load("/compose/app-compose.tmpl");

    /** Vars {@code app-compose.tmpl} references — the allow-list. */
    public static final java.util.Set<String> APP_VARS = java.util.Set.of(
            "APP_NAME", "ZONE_SLUG", "BASE_DOMAIN", "APP_IMAGE", "APP_PORT",
            "DEPLOY_ID", "CPU_APP", "MEM_APP");

    public String renderApp(Map<String, String> vars) {
        return substitute(appTemplate, vars);
    }

    static String substitute(String template, Map<String, String> vars) {
        Matcher m = VAR.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String replacement = vars.containsKey(key) ? vars.get(key) : m.group(0);
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String load(String resource) {
        try (InputStream in = ComposeTemplate.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing template " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + resource, e);
        }
    }
}
