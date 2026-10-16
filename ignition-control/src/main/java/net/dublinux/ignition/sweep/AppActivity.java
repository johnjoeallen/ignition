package net.dublinux.ignition.sweep;

import java.time.Duration;
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
 * Has an app been used lately? Two signals, either one is enough:
 * <ul>
 *   <li><b>traffic</b> — a line in the node Traefik's access log for the app's
 *       router (router name = compose project, {@code app-<slug>-<name>[-dev]}),
 *       not counting the console's own health probes ({@link AppHealthProbe#USER_AGENT});</li>
 *   <li><b>log output</b> — anything the app's web container wrote to
 *       stdout/stderr. A deploy or a Start always logs, so a fresh app is never idle.</li>
 * </ul>
 * Traffic is remembered per project in memory: each scan of a node reads its
 * Traefik log only since the previous scan (the first one after a restart reads
 * the whole window).
 */
@Component
public class AppActivity {

    private static final Logger log = LoggerFactory.getLogger(AppActivity.class);

    /** Traefik CLF access log: {@code … "<referer>" "<user-agent>" <n> "<router>@<provider>" "<url>" <ms>ms} */
    private static final Pattern ROUTER = Pattern.compile("\"([^\"@]+)@[a-z]+\"");

    private final DockerCli docker;

    /** project -> last time a scan saw traffic for it */
    private final Map<String, Instant> lastTraffic = new ConcurrentHashMap<>();
    /** docker host -> when its Traefik log was last read up to */
    private final Map<String, Instant> lastScan = new ConcurrentHashMap<>();

    public AppActivity(DockerCli docker) {
        this.docker = docker;
    }

    /** Read the node's Traefik access log since the last scan (at most {@code window}) and record traffic. */
    public void scanTraffic(String host, Duration window) {
        Instant now = Instant.now();
        Instant floor = now.minus(window);
        Instant since = lastScan.getOrDefault(host, floor);
        if (since.isBefore(floor)) {
            since = floor;
        }
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
            lastTraffic.put(project, now);
        }
        lastScan.put(host, now);
    }

    /** Router names (provider suffix dropped) with at least one non-probe request. */
    static Set<String> routersWithTraffic(String accessLog) {
        Set<String> seen = new HashSet<>();
        for (String line : accessLog.split("\n")) {
            if (line.contains("\"" + AppHealthProbe.USER_AGENT + "\"")) {
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

    /** Traffic for {@code project} seen at or after {@code cutoff} (needs a prior {@link #scanTraffic}). */
    public boolean hadTraffic(String project, Instant cutoff) {
        Instant t = lastTraffic.get(project);
        return t != null && !t.isBefore(cutoff);
    }

    /** Did the project's web container write any log line at or after {@code cutoff}? Unknown counts as yes. */
    public boolean loggedSince(String host, String project, Instant cutoff) {
        DockerCli.Result ps = docker.docker(host, List.of("ps", "-q",
                "--filter", "label=com.docker.compose.project=" + project,
                "--filter", "label=ignition.web=true"));
        if (!ps.ok()) {
            return true;
        }
        for (String id : ps.stdout().lines().map(String::strip).filter(s -> !s.isEmpty()).toList()) {
            DockerCli.Result r = docker.docker(host, List.of("logs", "--since",
                    Long.toString(cutoff.getEpochSecond()), "--tail", "1", id));
            if (!r.ok() || !r.stdout().isBlank() || !r.stderr().isBlank()) {
                return true;
            }
        }
        return false;
    }
}
