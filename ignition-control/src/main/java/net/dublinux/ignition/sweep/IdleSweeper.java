package net.dublinux.ignition.sweep;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;

import net.dublinux.ignition.config.IgnitionProperties;
import net.dublinux.ignition.zone.Zone;
import net.dublinux.ignition.zone.ZoneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reclaims zones idle past {@code ignition.sweep.ttl}. Idle = the zone's
 * {@code last-activity} epoch (bumped by provisioning and every deploy) is
 * older than the TTL. Today it only reports; the actual teardown wires in with
 * {@code ZoneService.destroy()} (DESIGN.md step 6).
 */
@Component
public class IdleSweeper {

    private static final Logger log = LoggerFactory.getLogger(IdleSweeper.class);

    private final IgnitionProperties props;
    private final ZoneRepository zones;

    public IdleSweeper(IgnitionProperties props, ZoneRepository zones) {
        this.props = props;
        this.zones = zones;
    }

    @Scheduled(fixedDelayString = "${ignition.sweep.interval}", initialDelay = 60_000)
    public void sweep() {
        Duration ttl = props.getSweep().getTtl();
        Instant cutoff = Instant.now().minus(ttl);
        for (Zone z : zones.findAll()) {
            Instant last = lastActivity(z.slug());
            if (last != null && last.isBefore(cutoff)) {
                log.info("zone {} idle since {} (> {}) — would reclaim", z.slug(), last, ttl);
            }
        }
    }

    private Instant lastActivity(String slug) {
        try {
            var p = zones.dir(slug).resolve("last-activity");
            if (!Files.isRegularFile(p)) {
                return null;
            }
            return Instant.ofEpochSecond(Long.parseLong(Files.readString(p).strip()));
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }
}
