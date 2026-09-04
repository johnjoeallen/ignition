package net.dublinux.ignition.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.dublinux.ignition.auth.CurrentUser;
import net.dublinux.ignition.provisioning.ProvisioningService;
import net.dublinux.ignition.sweep.IdleSweeper;
import net.dublinux.ignition.zone.ZoneService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * One-shot event start/end. {@code apply} queues a provision for every slug in
 * the list that isn't already a zone; {@code teardown} destroys every slug that
 * is. Closes the "no roster loop" gap — 80 zones was 80 CLI calls.
 */
@Controller
public class RosterController {

    private final ProvisioningService provisioning;
    private final ZoneService zones;
    private final IdleSweeper sweeper;
    private final CurrentUser currentUser;

    public RosterController(ProvisioningService provisioning, ZoneService zones, IdleSweeper sweeper,
                            CurrentUser currentUser) {
        this.provisioning = provisioning;
        this.zones = zones;
        this.sweeper = sweeper;
        this.currentUser = currentUser;
    }

    @GetMapping("/roster")
    public String roster() {
        return "roster";
    }

    @PostMapping("/roster/apply")
    public String apply(@RequestParam String slugs, Model model) {
        List<String> report = new ArrayList<>();
        Set<String> existing = new LinkedHashSet<>(zones.list().stream().map(z -> z.slug()).toList());
        var creator = currentUser.get().map(u -> u.id()).orElse(null);
        for (String slug : parse(slugs)) {
            if (existing.contains(slug)) {
                report.add(slug + " — already a zone, skipped");
                continue;
            }
            try {
                provisioning.submit(slug, "", "", creator);
                report.add(slug + " — queued");
            } catch (RuntimeException e) {
                report.add(slug + " — " + e.getMessage());
            }
        }
        model.addAttribute("report", report);
        model.addAttribute("slugs", slugs);
        return "roster";
    }

    @PostMapping("/roster/teardown")
    public String teardown(@RequestParam String slugs, Model model) {
        List<String> report = new ArrayList<>();
        Set<String> existing = new LinkedHashSet<>(zones.list().stream().map(z -> z.slug()).toList());
        for (String slug : parse(slugs)) {
            if (!existing.contains(slug)) {
                report.add(slug + " — not a zone, skipped");
                continue;
            }
            try {
                zones.destroy(slug, false);
                report.add(slug + " — destroyed");
            } catch (RuntimeException e) {
                report.add(slug + " — " + e.getMessage());
            }
        }
        model.addAttribute("report", report);
        model.addAttribute("slugs", slugs);
        return "roster";
    }

    @PostMapping("/sweep")
    public String sweepNow(Model model) {
        List<String> actions = sweeper.sweepNow();
        model.addAttribute("report", actions.isEmpty() ? List.of("nothing idle past the TTL") : actions);
        return "roster";
    }

    private static List<String> parse(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            int hash = line.indexOf('#');
            String content = (hash >= 0 ? line.substring(0, hash) : line);
            for (String tok : content.split("[\\s,]+")) {
                String s = tok.strip();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
