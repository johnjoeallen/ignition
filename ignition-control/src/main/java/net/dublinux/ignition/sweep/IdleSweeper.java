package net.dublinux.ignition.sweep;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import net.dublinux.ignition.config.IgnitionProperties;
import net.dublinux.ignition.zone.Zone;
import net.dublinux.ignition.zone.ZoneRepository;
import net.dublinux.ignition.zone.ZoneService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reclaims zones idle past {@code ignition.sweep.ttl} — the zone's
 * {@code last_activity} (bumped by provisioning and every deploy) older than the
 * TTL. Set {@code ignition.sweep.dry-run=true} to only report.
 */
@Component
public class IdleSweeper {

    private static final Logger log = LoggerFactory.getLogger(IdleSweeper.class);

    private final IgnitionProperties props;
    private final ZoneRepository zones;
    private final ZoneService zoneService;

    @Value("${ignition.sweep.dry-run:false}")
    private boolean dryRun;

    public IdleSweeper(IgnitionProperties props, ZoneRepository zones, ZoneService zoneService) {
        this.props = props;
        this.zones = zones;
        this.zoneService = zoneService;
    }

    @Scheduled(fixedDelayString = "${ignition.sweep.interval}", initialDelay = 60_000)
    public void sweep() {
        sweepNow();
    }

    /** Run one sweep now and return a line per zone acted on. */
    public List<String> sweepNow() {
        Duration ttl = props.getSweep().getTtl();
        Instant cutoff = Instant.now().minus(ttl);
        List<String> actions = new ArrayList<>();
        for (Zone z : zones.findAll()) {
            Instant last = z.lastActivity();
            if (last == null || !last.isBefore(cutoff)) {
                continue;
            }
            if (dryRun) {
                actions.add(z.slug() + ": idle since " + last + " — would reclaim (dry-run)");
                log.info("zone {} idle since {} — would reclaim (dry-run)", z.slug(), last);
                continue;
            }
            log.info("zone {} idle since {} (> {}) — reclaiming", z.slug(), last, ttl);
            try {
                zoneService.destroy(z.slug(), false);
                actions.add(z.slug() + ": reclaimed (idle since " + last + ")");
            } catch (RuntimeException e) {
                log.warn("failed to reclaim idle zone {}", z.slug(), e);
                actions.add(z.slug() + ": reclaim FAILED — " + e.getMessage());
            }
        }
        return actions;
    }
}
