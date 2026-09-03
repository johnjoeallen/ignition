package net.dublinux.ignition.zone;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * Zone lifecycle. Today: list / get only — provisioning, move and destroy land
 * with {@code ProvisioningService} (DESIGN.md steps 5–6).
 */
@Service
public class ZoneService {

    private final ZoneRepository zones;

    public ZoneService(ZoneRepository zones) {
        this.zones = zones;
    }

    public List<Zone> list() {
        return zones.findAll();
    }

    public Optional<Zone> get(String slug) {
        return zones.find(slug);
    }

    public String zoneToken(String slug) {
        return zones.secret(slug, "zone-token");
    }

    public String deployToken(String slug) {
        return zones.secret(slug, "deploy-token");
    }
}
