package net.dublinux.ignition.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.dublinux.ignition.zone.Zone;
import net.dublinux.ignition.zone.ZoneRepository;
import net.dublinux.ignition.zone.ZoneService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Backstop for PR previews. The {@code pr-preview.yml} workflow tears a preview
 * down when its PR closes; this sweep catches the ones that slipped through (a
 * missed webhook, a failed teardown job, a repo whose workflow predates the
 * feature). Preview deployments are the {@code app} rows whose name matches
 * {@code <repo>-pr-<n>}; a preview whose PR is no longer open is undeployed and
 * its {@code :pr-<n>} image tag dropped.
 */
@Component
public class PreviewReaper {

    private static final Logger log = LoggerFactory.getLogger(PreviewReaper.class);
    private static final Pattern PREVIEW = Pattern.compile("^(.+)-pr-(\\d+)$");

    private final AppRepository apps;
    private final ZoneRepository zones;
    private final ZoneService zoneService;
    private final AppService appService;

    public PreviewReaper(AppRepository apps, ZoneRepository zones,
                         ZoneService zoneService, AppService appService) {
        this.apps = apps;
        this.zones = zones;
        this.zoneService = zoneService;
        this.appService = appService;
    }

    @Scheduled(fixedDelayString = "${ignition.preview.reap-interval:1h}", initialDelay = 300_000)
    public void reap() {
        reapNow();
    }

    /** Run one sweep now; returns a line per preview reclaimed. */
    public List<String> reapNow() {
        List<String> actions = new ArrayList<>();
        for (Zone z : zones.findAll()) {
            for (DeployedApp app : apps.findByZone(z.slug())) {
                Matcher m = PREVIEW.matcher(app.name());
                if (!m.matches()) {
                    continue;
                }
                String repo = m.group(1);
                int pr = Integer.parseInt(m.group(2));
                Set<Integer> open = zoneService.openPullNumbers(z.slug(), repo);
                if (open == null || open.contains(pr)) {
                    continue; // repo/API unreachable, or the PR is still open — leave it
                }
                try {
                    appService.undeploy(z.slug(), app.name(), Channel.RELEASE);
                    zoneService.deletePackageTag(z.slug(), repo, "pr-" + pr);
                    actions.add(z.slug() + "/" + app.name() + ": PR #" + pr + " not open — reclaimed");
                    log.info("preview {}/{} reclaimed (PR #{} closed)", z.slug(), app.name(), pr);
                } catch (RuntimeException e) {
                    log.warn("preview {}/{} reclaim failed", z.slug(), app.name(), e);
                    actions.add(z.slug() + "/" + app.name() + ": reclaim FAILED — " + e.getMessage());
                }
            }
        }
        return actions;
    }
}
