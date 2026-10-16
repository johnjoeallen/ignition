package net.dublinux.ignition.sweep;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.dublinux.ignition.app.AppHealthProbe;
import net.dublinux.ignition.docker.DockerCli;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Has an app been used lately? One signal: a request in the node Traefik's
 * access log for the app's router (router name = compose project,
 * {@code app-<slug>-<name>[-dev]}), not counting the console's own health probes
 * ({@link AppHealthProbe#USER_AGENT}). Each scan reads a node's log only since
 * the previous scan (one sweep tick), so Docker's log rotation can't matter
 * unless a node writes more than the retained size in a single tick. The last
 * request time per project is persisted ({@link AppTraffic}); this controller's
 * start time acts as a floor, so a restart — which can miss traffic from just
 * before it — never makes an app look idle sooner.
 */
@Component
public class AppActivity {

    private static final Logger log = LoggerFactory.getLogger(AppActivity.class);

    /** Traefik CLF access log: {@code … "<referer>" "<user-agent>" <n> "<router>@<provider>" "<url>" <ms>ms} */
    private static final Pattern ROUTER = Pattern.compile("\"([^\"@]+)@[a-z]+\"");

    private final DockerCli docker;
    private final AppTrafficRepository traffic;
    private final Instant started = Instant.now();

    /** docker host -> when its Traefik log was last read up to */
    private final Map<String, Instant> lastScan = new ConcurrentHashMap<>();

    public AppActivity(DockerCli docker, AppTrafficRepository traffic) {
        this.docker = docker;
        this.traffic = traffic;
    }

    /** Read the node's Traefik access log since the last scan (or since this controller started) and record traffic. */
    public void scanTraffic(String host) {
        Instant now = Instant.now();
        Instant since = lastScan.getOrDefault(host, started);
        DockerCli.Result ps = docker.docker(host, List.of("ps", "-q",
                "--filter", "label=com.docker.compose.project=ignition-core",
                "--filter", "label=com.docker.compose.service=traefik"));
        String id = ps.stdout().lines().map(String::strip).filter(s -> !s.isEmpty()).findFirst().orElse(null);
        if (!ps.ok() || id == null) {
            log.warn("no Traefik on {} — can't read traffic", host);
            return;
        }
        DockerCli.Result logs = docker.docker(host, List.of("logs", "--since",
                Long.toString(since.getEpochSecond()), id));
        if (!logs.ok()) {
            log.warn("reading Traefik log on {} failed: {}", host, logs.stderr().strip());
            return;
        }
        for (String project : routersWithTraffic(logs.stdout() + "\n" + logs.stderr())) {
            if (project.startsWith("app-")) {
                traffic.save(new AppTraffic(project, now));
            }
        }
        lastScan.put(host, now);
    }

    /** Router names (provider suffix dropped) with at least one non-probe request. */
    static Set<String> routersWithTraffic(String accessLog) {
        Set<String> seen = new HashSet<>();
        for (String line : accessLog.split("\n")) {
            if (line.contains(AppHealthProbe.PROBE_PARAM) || line.contains("\"" + AppHealthProbe.USER_AGENT + "\"")) {
                continue;
            }
            Matcher m = ROUTER.matcher(line);
            String router = null;
            while (m.find()) {
                router = m.group(1); // the router is the last quoted name@provider on the line
            }
            if (router != null) {
                seen.add(router);
            }
        }
        return seen;
    }

    /**
     * The last moment {@code project} is known to have been in use: its last
     * request, or this controller's start if later. Callers also fold in when
     * the app was last started.
     */
    public Instant lastActive(String project) {
        Instant t = traffic.findById(project).map(AppTraffic::lastTrafficAt).orElse(started);
        return t.isBefore(started) ? started : t;
    }
}
