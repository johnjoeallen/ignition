package net.dublinux.ignition.sweep;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.dublinux.ignition.app.AppService;
import net.dublinux.ignition.app.DeployedApp;
import net.dublinux.ignition.app.DevDeployment;
import net.dublinux.ignition.config.IgnitionProperties;
import net.dublinux.ignition.node.NodeRepository;
import net.dublinux.ignition.zone.Zone;
import net.dublinux.ignition.zone.ZoneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stops apps nobody is using. An app — each release and each dev deployment,
 * judged on its own — is idle when, for the whole of {@code ignition.sweep.ttl},
 * it has had no traffic through its router and its web container has written
 * no log output ({@link AppActivity}). An idle app gets {@code docker compose
 * stop}; nothing is removed — no {@code down}, no {@code -v}, the zone, its
 * Forgejo and every row stay. The console's <b>Start</b> brings it back.
 * Destroying a zone is only ever a manual platform-admin action. Set
 * {@code ignition.sweep.dry-run=true} to only report.
 */
@Component
public class IdleSweeper {

    private static final Logger log = LoggerFactory.getLogger(IdleSweeper.class);

    private final IgnitionProperties props;
    private final ZoneRepository zones;
    private final NodeRepository nodes;
    private final AppService apps;
    private final AppActivity activity;

    @Value("${ignition.sweep.dry-run:false}")
    private boolean dryRun;

    public IdleSweeper(IgnitionProperties props, ZoneRepository zones, NodeRepository nodes,
                       AppService apps, AppActivity activity) {
        this.props = props;
        this.zones = zones;
        this.nodes = nodes;
        this.apps = apps;
        this.activity = activity;
    }

    @Scheduled(fixedDelayString = "${ignition.sweep.interval}", initialDelay = 60_000)
    public void sweep() {
        sweepNow();
    }

    /** Run one sweep now and return a line per app acted on. */
    public List<String> sweepNow() {
        Duration ttl = props.getSweep().getTtl();
        Instant cutoff = Instant.now().minus(ttl);
        List<String> actions = new ArrayList<>();
        Set<String> scanned = new HashSet<>();
        for (Zone z : zones.findAll()) {
            List<DeployedApp> releases = apps.listForZone(z.slug()).stream()
                    .filter(DeployedApp::running).toList();
            List<DevDeployment> devs = apps.devDeploymentsForZone(z.slug()).stream()
                    .filter(DevDeployment::running).toList();
            if (releases.isEmpty() && devs.isEmpty()) {
                continue;
            }
            String host = nodes.findById(z.node()).map(n -> n.dockerHost()).orElse("local");
            if (scanned.add(host)) {
                activity.scanTraffic(host, ttl);
            }
            for (DeployedApp a : releases) {
                sweepOne(host, z.slug(), a.name(), false, cutoff, ttl, actions);
            }
            for (DevDeployment d : devs) {
                sweepOne(host, z.slug(), d.name(), true, cutoff, ttl, actions);
            }
        }
        return actions;
    }

    private void sweepOne(String host, String slug, String name, boolean dev,
                          Instant cutoff, Duration ttl, List<String> actions) {
        String project = "app-" + slug + "-" + name + (dev ? "-dev" : "");
        String what = slug + "/" + name + (dev ? " (dev)" : "");
        if (activity.hadTraffic(project, cutoff) || activity.loggedSince(host, project, cutoff)) {
            return;
        }
        if (dryRun) {
            actions.add(what + ": no traffic or logs for " + ttl + " — would stop (dry-run)");
            log.info("app {} idle (no traffic or logs for {}) — would stop (dry-run)", what, ttl);
            return;
        }
        log.info("app {} idle (no traffic or logs for {}) — stopping", what, ttl);
        try {
            if (dev) {
                apps.stopDev(slug, name);
            } else {
                apps.stop(slug, name);
            }
            actions.add(what + ": stopped (no traffic or logs for " + ttl + ")");
        } catch (RuntimeException e) {
            log.warn("failed to stop idle app {}", what, e);
            actions.add(what + ": stop FAILED — " + e.getMessage());
        }
    }
}
