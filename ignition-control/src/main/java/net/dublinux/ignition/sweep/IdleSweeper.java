package net.dublinux.ignition.sweep;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.dublinux.ignition.app.AppConfig;
import net.dublinux.ignition.app.AppConfigRepository;
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
 * judged on its own — is idle when, for its idle time, Traefik has seen no
 * request for it ({@link AppActivity}). The idle time is per app (set by a team
 * admin on the Apps table, deployed or not): unset = {@code ignition.sweep.ttl}, {@code 0} =
 * never stop. An idle app gets {@code docker compose stop}; nothing is removed —
 * no {@code down}, no {@code -v}, the zone, its Forgejo and every row stay. The
 * console's <b>Start</b> brings it back. Destroying a zone is only ever a manual
 * platform-admin action. Set {@code ignition.sweep.dry-run=true} to only report.
 * Covers every app on every node: each tick walks all zones.
 */
@Component
public class IdleSweeper {

    private static final Logger log = LoggerFactory.getLogger(IdleSweeper.class);

    private final IgnitionProperties props;
    private final ZoneRepository zones;
    private final NodeRepository nodes;
    private final AppService apps;
    private final AppActivity activity;
    private final AppConfigRepository configs;

    @Value("${ignition.sweep.dry-run:false}")
    private boolean dryRun;

    public IdleSweeper(IgnitionProperties props, ZoneRepository zones, NodeRepository nodes,
                       AppService apps, AppActivity activity, AppConfigRepository configs) {
        this.props = props;
        this.zones = zones;
        this.nodes = nodes;
        this.apps = apps;
        this.activity = activity;
        this.configs = configs;
    }

    @Scheduled(fixedDelayString = "${ignition.sweep.interval}", initialDelay = 60_000)
    public void sweep() {
        sweepNow();
    }

    /** Run one sweep now and return a line per app acted on. */
    public List<String> sweepNow() {
        Duration dflt = props.getSweep().getTtl();
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
            Map<String, Integer> ttls = new java.util.HashMap<>();
            for (AppConfig c : configs.findByZone(z.slug())) {
                ttls.put(c.name(), c.idleTtlMinutes());
            }
            String host = nodes.findById(z.node()).map(n -> n.dockerHost()).orElse("local");
            if (scanned.add(host)) {
                try {
                    activity.scanTraffic(host);
                } catch (RuntimeException e) {
                    log.warn("traffic scan of {} failed — leaving its apps alone this tick", host, e);
                    scanned.remove(host);
                    continue;
                }
            }
            for (DeployedApp a : releases) {
                sweepOne(z.slug(), a.name(), false, ttls.get(a.name()), a.activeSince(), dflt, actions);
            }
            for (DevDeployment d : devs) {
                sweepOne(z.slug(), d.name(), true, ttls.get(d.name()), d.activeSince(), dflt, actions);
            }
        }
        return actions;
    }

    private void sweepOne(String slug, String name, boolean dev, Integer ttlMinutes,
                          Instant activeSince, Duration dflt, List<String> actions) {
        if (ttlMinutes != null && ttlMinutes == 0) {
            return; // never stop
        }
        Duration ttl = ttlMinutes == null ? dflt : Duration.ofMinutes(ttlMinutes);
        String project = "app-" + slug + "-" + name + (dev ? "-dev" : "");
        String what = slug + "/" + name + (dev ? " (dev)" : "");
        Instant last = activity.lastActive(project);
        if (activeSince != null && activeSince.isAfter(last)) {
            last = activeSince;
        }
        if (last.isAfter(Instant.now().minus(ttl))) {
            return;
        }
        if (dryRun) {
            actions.add(what + ": no traffic for " + ttl + " — would stop (dry-run)");
            log.info("app {} idle (no traffic for {}) — would stop (dry-run)", what, ttl);
            return;
        }
        log.info("app {} idle (no traffic for {}) — stopping", what, ttl);
        try {
            if (dev) {
                apps.stopDev(slug, name);
            } else {
                apps.stop(slug, name);
            }
            actions.add(what + ": stopped (no traffic for " + ttl + ")");
        } catch (RuntimeException e) {
            log.warn("failed to stop idle app {}", what, e);
            actions.add(what + ": stop FAILED — " + e.getMessage());
        }
    }
}
