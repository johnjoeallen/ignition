package net.dublinux.ignition.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.dublinux.ignition.config.IgnitionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Turns an app repo's own {@code compose.yaml} into the compose file the control
 * plane runs on the zone's node — or, when the repo has none, synthesises the
 * single-service file that {@code app-compose.tmpl} used to render.
 *
 * <p>The transform is the security boundary (see the CLAUDE.md "concentrated
 * blast radius" note): a small set of keys is rejected outright, a few local-dev
 * conveniences are stripped, and every service is pinned to a resource limit and
 * to the project's private network — only the one {@code ignition.web=true}
 * service is put on {@code traefik-public} and given a router.
 *
 * <p>Built in code, not templated — same reasoning as {@code runner-config.yml}
 * (variable structure). YAML is parsed with a {@link SafeConstructor} (no
 * arbitrary type instantiation) and re-dumped block-style.
 */
@Component
public class AppComposeBuilder {

    private static final Logger log = LoggerFactory.getLogger(AppComposeBuilder.class);

    public static final List<String> COMPOSE_FILENAMES =
            List.of("compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml");

    /** Service keys that are never allowed — a hard deploy failure. */
    private static final List<String> REJECTED_SERVICE_KEYS = List.of(
            "privileged", "cap_add", "devices", "network_mode", "pid", "ipc",
            "userns_mode", "security_opt", "sysctls", "extends", "env_file");

    /** The label that marks the one routed service. */
    static final String WEB_LABEL = "ignition.web";

    private static final String TRAEFIK_NET = "traefik-public";

    private final IgnitionProperties props;

    public AppComposeBuilder(IgnitionProperties props) {
        this.props = props;
    }

    /**
     * @param composeYaml the repo's compose file contents, or {@code null}/blank
     *                    for the synthesised single-service case
     * @param image       the web service's image (from {@code /deploy}) — always wins
     * @param port        the web container port to route to when the compose
     *                    doesn't declare one
     */
    public String build(String slug, String name, Channel channel,
                        String image, int port, String composeYaml) {
        Map<String, Object> root = (composeYaml == null || composeYaml.isBlank())
                ? synthesised()
                : parse(composeYaml);

        Map<String, Object> services = asMap(root.get("services"));
        if (services == null || services.isEmpty()) {
            throw new ComposeSpecException("compose.yaml declares no services");
        }
        rejectTopLevel(root);

        String webName = findWebService(services);
        String project = "app-" + slug + "-" + name + channel.projectSuffix();
        String host = "%s.%s.%s.%s".formatted(name, channel.subdomain(), slug, props.getBaseDomain());

        int webPort = port;
        for (var e : services.entrySet()) {
            String svcName = e.getKey();
            Map<String, Object> svc = asMap(e.getValue());
            if (svc == null) {
                throw new ComposeSpecException("service '" + svcName + "' has no definition");
            }
            boolean isWeb = svcName.equals(webName);
            rejectDisallowed(svcName, svc);
            handleBuild(svcName, svc, isWeb);
            Integer declared = stripPortsReturningContainerPort(svc);
            svc.remove("container_name");
            svc.put("restart", "unless-stopped");
            collectAndValidateVolumes(svcName, svc);

            if (isWeb) {
                if (declared != null) {
                    webPort = declared;
                }
                svc.put("image", image);
                putWebNetworks(svc);
                putWebLabels(svc, project, host);
                Map<String, Object> l = labelsAsMap(svc);
                l.put("ignition.zone", slug);
                l.put("ignition.app", name);
                svc.put("labels", l);
                ensurePortEnv(svc, webPort);
                limit(svc, props.getQuotas().getCpuApp(), props.getQuotas().getMemApp());
            } else {
                requireCleanImage(svcName, svc);
                pinToDefaultNetwork(svc);
                stripTraefikLabels(svc);
                limit(svc, props.getQuotas().getCpuSvc(), props.getQuotas().getMemSvc());
            }
        }

        root.put("name", project);
        root.put("services", services);
        putTopLevelNetworks(root);
        declareNamedVolumes(root, services);
        root.remove("version");

        return "# generated by ignition-control from the app's compose.yaml — do not edit\n"
                + dump(root);
    }

    // --- the no-file case: what app-compose.tmpl used to render ----------------

    private Map<String, Object> synthesised() {
        Map<String, Object> web = new LinkedHashMap<>();
        web.put("labels", new LinkedHashMap<>(Map.of(WEB_LABEL, "true")));
        Map<String, Object> services = new LinkedHashMap<>();
        services.put("web", web);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("services", services);
        return root;
    }

    // --- parse ---------------------------------------------------------------

    private Map<String, Object> parse(String yaml) {
        LoaderOptions opts = new LoaderOptions();
        opts.setMaxAliasesForCollections(50);
        opts.setCodePointLimit(512 * 1024);
        try {
            Object o = new Yaml(new SafeConstructor(opts)).load(yaml);
            if (!(o instanceof Map)) {
                throw new ComposeSpecException("compose.yaml must be a YAML mapping");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            return m;
        } catch (YAMLException e) {
            throw new ComposeSpecException("compose.yaml isn't valid YAML: " + e.getMessage());
        }
    }

    // --- rejects -----------------------------------------------------------

    private void rejectTopLevel(Map<String, Object> root) {
        for (String k : List.of("configs", "secrets")) {
            if (root.containsKey(k)) {
                throw new ComposeSpecException("top-level '" + k + ":' isn't supported — "
                        + "inline the values or move them to compose.override.yaml");
            }
        }
    }

    private void rejectDisallowed(String svcName, Map<String, Object> svc) {
        for (String k : REJECTED_SERVICE_KEYS) {
            if (svc.containsKey(k)) {
                throw new ComposeSpecException("service '" + svcName + "': '" + k
                        + ":' isn't allowed on a deployed app — put it in compose.override.yaml (local only)");
            }
        }
    }

    private void handleBuild(String svcName, Map<String, Object> svc, boolean isWeb) {
        if (!svc.containsKey("build")) {
            return;
        }
        if (isWeb) {
            svc.remove("build"); // the image comes from /deploy
        } else {
            throw new ComposeSpecException("service '" + svcName + "': the platform can't build it — "
                    + "use a prebuilt 'image:' (any registry; a small blocklist applies)");
        }
    }

    private void requireCleanImage(String svcName, Map<String, Object> svc) {
        Object img = svc.get("image");
        if (!(img instanceof String s) || s.isBlank()) {
            throw new ComposeSpecException("service '" + svcName + "' needs an 'image:'");
        }
        String repo = s.replaceFirst("@sha256:.*$", "").replaceFirst(":[^/:]+$", "");
        for (String pattern : props.getServices().getBlockedImages()) {
            if (globMatch(pattern.trim(), s) || globMatch(pattern.trim(), repo)) {
                throw new ComposeSpecException("service '" + svcName + "': image '" + s
                        + "' is blocked by platform policy");
            }
        }
    }

    // --- volumes ----------------------------------------------------------

    private void collectAndValidateVolumes(String svcName, Map<String, Object> svc) {
        Object v = svc.get("volumes");
        if (v == null) {
            return;
        }
        if (!(v instanceof List<?> list)) {
            throw new ComposeSpecException("service '" + svcName + "': 'volumes:' must be a list");
        }
        for (Object entry : list) {
            if (entry instanceof String s) {
                String source = s.contains(":") ? s.substring(0, s.indexOf(':')) : "";
                if (isHostPath(source)) {
                    throw new ComposeSpecException("service '" + svcName + "': bind mount '" + s
                            + "' isn't allowed — use a named volume, or compose.override.yaml for local dev");
                }
            } else if (entry instanceof Map<?, ?> m) {
                if ("bind".equals(String.valueOf(m.get("type")))) {
                    throw new ComposeSpecException("service '" + svcName
                            + "': bind mounts aren't allowed — use a named volume");
                }
            }
        }
    }

    private static boolean isHostPath(String source) {
        return source.startsWith("/") || source.startsWith("./") || source.startsWith("../")
                || source.startsWith("~") || source.startsWith("$") || source.startsWith(".\\")
                || source.matches("^[A-Za-z]:[\\\\/].*");
    }

    private void declareNamedVolumes(Map<String, Object> root, Map<String, Object> services) {
        Map<String, Object> declared = asMap(root.get("volumes"));
        if (declared == null) {
            declared = new LinkedHashMap<>();
        }
        for (Object svcObj : services.values()) {
            Map<String, Object> svc = asMap(svcObj);
            if (svc == null || !(svc.get("volumes") instanceof List<?> list)) {
                continue;
            }
            for (Object entry : list) {
                if (entry instanceof String s && s.contains(":")) {
                    String source = s.substring(0, s.indexOf(':'));
                    if (!isHostPath(source) && !source.isEmpty() && !declared.containsKey(source)) {
                        declared.put(source, null);
                    }
                }
            }
        }
        if (!declared.isEmpty()) {
            root.put("volumes", declared);
        }
    }

    // --- ports ----------------------------------------------------------

    /** Removes host {@code ports:} bindings; returns the container port to route to, if it can tell. */
    private Integer stripPortsReturningContainerPort(Map<String, Object> svc) {
        Integer found = null;
        Object ports = svc.remove("ports");
        if (ports instanceof List<?> list) {
            for (Object p : list) {
                Integer cp = containerPortOf(p);
                if (cp != null) {
                    found = cp;
                    break;
                }
            }
        }
        if (found == null && svc.get("expose") instanceof List<?> exp && !exp.isEmpty()) {
            found = intOrNull(exp.get(0));
        }
        return found;
    }

    private Integer containerPortOf(Object p) {
        if (p instanceof Integer i) {
            return i;
        }
        if (p instanceof Map<?, ?> m) {
            return intOrNull(m.get("target"));
        }
        if (p instanceof String s) {
            // "8080", "8080:80", "127.0.0.1:8080:80", "80/tcp"
            String spec = s.split("/")[0];
            String[] parts = spec.split(":");
            return intOrNull(parts[parts.length - 1]);
        }
        return null;
    }

    // --- networks -------------------------------------------------------

    private void putWebNetworks(Map<String, Object> svc) {
        Object nets = svc.get("networks");
        if (nets instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mm = (Map<String, Object>) m;
            mm.putIfAbsent("default", null);
            mm.putIfAbsent(TRAEFIK_NET, null);
        } else {
            List<String> list = nets instanceof List<?> l
                    ? new ArrayList<>(l.stream().map(String::valueOf).toList())
                    : new ArrayList<>();
            if (!list.contains("default")) {
                list.add("default");
            }
            if (!list.contains(TRAEFIK_NET)) {
                list.add(TRAEFIK_NET);
            }
            svc.put("networks", list);
        }
    }

    private void pinToDefaultNetwork(Map<String, Object> svc) {
        Object nets = svc.get("networks");
        if (nets instanceof Map<?, ?> m && m.containsKey(TRAEFIK_NET)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mm = (Map<String, Object>) m;
            mm.remove(TRAEFIK_NET);
            log.info("compose transform: dropped {} from a non-web service", TRAEFIK_NET);
        } else if (nets instanceof List<?> l && l.stream().map(String::valueOf).anyMatch(TRAEFIK_NET::equals)) {
            List<String> list = new ArrayList<>(l.stream().map(String::valueOf).toList());
            list.remove(TRAEFIK_NET);
            svc.put("networks", list.isEmpty() ? null : list);
            log.info("compose transform: dropped {} from a non-web service", TRAEFIK_NET);
        }
    }

    private void putTopLevelNetworks(Map<String, Object> root) {
        Map<String, Object> nets = asMap(root.get("networks"));
        if (nets == null) {
            nets = new LinkedHashMap<>();
        }
        nets.put(TRAEFIK_NET, new LinkedHashMap<>(Map.of("external", true)));
        root.put("networks", nets);
    }

    // --- labels --------------------------------------------------------

    private void putWebLabels(Map<String, Object> svc, String project, String host) {
        Map<String, Object> labels = labelsAsMap(svc);
        labels.put("traefik.enable", "true");
        labels.put("traefik.docker.network", TRAEFIK_NET);
        labels.put("traefik.http.routers." + project + ".rule", "Host(`" + host + "`)");
        labels.put("traefik.http.routers." + project + ".entrypoints", "websecure");
        labels.put("traefik.http.routers." + project + ".tls", "true");
        labels.put("traefik.http.services." + project + ".loadbalancer.server.port", "");
        // ign-control stamps this on every deployed web container — the per-node
        // Watchtower then rolls it forward on a new image digest (app-compose.tmpl
        // used to carry it).
        labels.put("com.centurylinklabs.watchtower.enable", "true");
        svc.put("labels", labels);
    }

    private void stripTraefikLabels(Map<String, Object> svc) {
        if (!svc.containsKey("labels")) {
            return;
        }
        Map<String, Object> labels = labelsAsMap(svc);
        labels.keySet().removeIf(k -> k.startsWith("traefik."));
        svc.put("labels", labels);
    }

    private Map<String, Object> labelsAsMap(Map<String, Object> svc) {
        Object l = svc.get("labels");
        Map<String, Object> out = new LinkedHashMap<>();
        if (l instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        } else if (l instanceof List<?> list) {
            for (Object e : list) {
                String s = String.valueOf(e);
                int eq = s.indexOf('=');
                if (eq >= 0) {
                    out.put(s.substring(0, eq), s.substring(eq + 1));
                } else {
                    out.put(s, "");
                }
            }
        }
        return out;
    }

    // --- web service helpers ---------------------------------------------

    private String findWebService(Map<String, Object> services) {
        List<String> web = new ArrayList<>();
        for (var e : services.entrySet()) {
            Map<String, Object> svc = asMap(e.getValue());
            if (svc != null && "true".equals(String.valueOf(labelsAsMap(svc).get(WEB_LABEL)))) {
                web.add(e.getKey());
            }
        }
        if (web.isEmpty()) {
            throw new ComposeSpecException("no web service — mark exactly one service with "
                    + "labels: { " + WEB_LABEL + ": \"true\" }");
        }
        if (web.size() > 1) {
            throw new ComposeSpecException("more than one service is marked " + WEB_LABEL
                    + " (" + String.join(", ", web) + ") — mark exactly one");
        }
        return web.get(0);
    }

    private void ensurePortEnv(Map<String, Object> svc, int webPort) {
        Object env = svc.get("environment");
        if (env instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mm = (Map<String, Object>) m;
            mm.putIfAbsent("PORT", String.valueOf(webPort));
        } else if (env instanceof List<?> list) {
            boolean has = list.stream().map(String::valueOf).anyMatch(s -> s.equals("PORT") || s.startsWith("PORT="));
            if (!has) {
                List<Object> l = new ArrayList<>(list);
                l.add("PORT=" + webPort);
                svc.put("environment", l);
            }
        } else {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("PORT", String.valueOf(webPort));
            svc.put("environment", m);
        }
        // fill the loadbalancer port now that we know it
        Map<String, Object> labels = labelsAsMap(svc);
        labels.entrySet().stream()
                .filter(en -> en.getKey().endsWith(".loadbalancer.server.port"))
                .forEach(en -> en.setValue(String.valueOf(webPort)));
        svc.put("labels", labels);
    }

    // --- limits -------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void limit(Map<String, Object> svc, double cpus, String memory) {
        Map<String, Object> deploy = (Map<String, Object>) svc.computeIfAbsent("deploy", k -> new LinkedHashMap<>());
        Map<String, Object> resources = (Map<String, Object>) deploy.computeIfAbsent("resources", k -> new LinkedHashMap<>());
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("cpus", String.valueOf(cpus));
        limits.put("memory", memory);
        resources.put("limits", limits);
    }

    // --- dump -------------------------------------------------------

    private String dump(Map<String, Object> root) {
        DumperOptions o = new DumperOptions();
        o.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        o.setPrettyFlow(true);
        o.setIndent(2);
        o.setWidth(4096);
        return new Yaml(o).dump(root);
    }

    // --- small helpers -------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static Integer intOrNull(Object o) {
        if (o instanceof Integer i) {
            return i;
        }
        try {
            return o == null ? null : Integer.valueOf(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Tiny glob: {@code *} matches any run of characters. Anchored. */
    static boolean globMatch(String pattern, String value) {
        if (pattern.isEmpty()) {
            return false;
        }
        StringBuilder re = new StringBuilder("^");
        for (char c : pattern.toCharArray()) {
            if (c == '*') {
                re.append(".*");
            } else {
                re.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        re.append('$');
        return value.matches(re.toString());
    }
}
