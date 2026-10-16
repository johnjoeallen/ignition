package net.dublinux.ignition.wake;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.dublinux.ignition.app.AppRepository;
import net.dublinux.ignition.app.AppService;
import net.dublinux.ignition.app.DevDeploymentRepository;
import net.dublinux.ignition.zone.ZoneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Wake-on-request. A stopped app has no Traefik route, so its URL falls through
 * to a low-priority catch-all route into this control plane ({@link WakeFilter}).
 * This decides what the visitor sees, and starts an app the idle sweep stopped —
 * never one a person stopped on purpose.
 */
@Service
public class WakeService {

    private static final Logger log = LoggerFactory.getLogger(WakeService.class);

    /** {@code <name>.<apps|dev>.<slug>.<base domain>} */
    private static final Pattern HOST = Pattern.compile("^([a-z0-9-]+)\\.(apps|dev)\\.([a-z0-9-]+)\\.(.+)$");

    public record Target(String name, String slug, boolean dev) {
        String project() {
            return "app-" + slug + "-" + name + (dev ? "-dev" : "");
        }
    }

    public enum State {
        /** No such app (or zone) at this address. */
        UNKNOWN,
        /** Stopped by a person — stays stopped until Start is pressed. */
        TEAM_STOPPED,
        /** Stopped by the idle sweep — a browser visit will start it. */
        SLEEPING,
        /** Starting now (or marked running but its route isn't up yet). */
        WAKING
    }

    private final AppRepository apps;
    private final DevDeploymentRepository devApps;
    private final ZoneRepository zones;
    private final AppService appService;
    private final Set<String> waking = ConcurrentHashMap.newKeySet();

    public WakeService(AppRepository apps, DevDeploymentRepository devApps, ZoneRepository zones,
                       AppService appService) {
        this.apps = apps;
        this.devApps = devApps;
        this.zones = zones;
        this.appService = appService;
    }

    /** The app a Host header names, if it has the app-host shape and the zone's base domain. */
    public Optional<Target> parse(String host) {
        if (host == null) {
            return Optional.empty();
        }
        Matcher m = HOST.matcher(host.toLowerCase());
        if (!m.matches()) {
            return Optional.empty();
        }
        return zones.find(m.group(3))
                .filter(z -> z.baseDomain().equalsIgnoreCase(m.group(4)))
                .map(z -> new Target(m.group(1), z.slug(), "dev".equals(m.group(2))));
    }

    public State state(Target t) {
        if (waking.contains(t.project())) {
            return State.WAKING;
        }
        boolean running;
        boolean idleStopped;
        if (t.dev()) {
            var d = devApps.findByZoneAndName(t.slug(), t.name());
            if (d.isEmpty()) {
                return State.UNKNOWN;
            }
            running = d.get().running();
            idleStopped = d.get().idleStopped();
        } else {
            var a = apps.findByZoneAndName(t.slug(), t.name());
            if (a.isEmpty()) {
                return State.UNKNOWN;
            }
            running = a.get().running();
            idleStopped = a.get().idleStopped();
        }
        if (running) {
            return State.WAKING; // started, route not registered yet
        }
        return idleStopped ? State.SLEEPING : State.TEAM_STOPPED;
    }

    /** Start a sleeping app in the background. No-op unless it is {@link State#SLEEPING}. */
    public void wake(Target t) {
        if (state(t) != State.SLEEPING || !waking.add(t.project())) {
            return;
        }
        log.info("waking {} on request", t.project());
        Thread.ofVirtual().name("wake-" + t.project()).start(() -> {
            try {
                if (t.dev()) {
                    appService.startDev(t.slug(), t.name());
                } else {
                    appService.start(t.slug(), t.name());
                }
            } catch (RuntimeException e) {
                log.warn("wake of {} failed", t.project(), e);
            } finally {
                waking.remove(t.project());
            }
        });
    }
}
