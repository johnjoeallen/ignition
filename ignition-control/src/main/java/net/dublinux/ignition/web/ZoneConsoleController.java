package net.dublinux.ignition.web;

import net.dublinux.ignition.app.AppService;
import net.dublinux.ignition.security.IgnitionPrincipal;
import net.dublinux.ignition.zone.ZoneService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * The zone-admin console at {@code admin.<slug>.<BASE_DOMAIN>}. The zone is the
 * one in the caller's token. Users / Repositories / Release / runner-restart
 * are ported from {@code ign-control.py} in a later step; for now this shows
 * the zone's own status and apps.
 */
@Controller
public class ZoneConsoleController {

    private final ZoneService zones;
    private final AppService apps;

    public ZoneConsoleController(ZoneService zones, AppService apps) {
        this.zones = zones;
        this.apps = apps;
    }

    @GetMapping("/z")
    public String zone(Model model) {
        IgnitionPrincipal p = CurrentPrincipal.get();
        if (p == null || p.kind() != IgnitionPrincipal.Kind.ZONE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        var zone = zones.get(p.slug())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such zone"));
        model.addAttribute("zone", zone);
        model.addAttribute("apps", apps.listForZone(p.slug()));
        return "zone";
    }
}
