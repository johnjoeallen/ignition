package net.dublinux.ignition.security;

import java.util.Optional;

import net.dublinux.ignition.config.IgnitionProperties;
import net.dublinux.ignition.zone.Zone;
import net.dublinux.ignition.zone.ZoneRepository;
import org.springframework.stereotype.Component;

/** Maps an opaque token to an {@link IgnitionPrincipal}, constant-time. */
@Component
public class TokenResolver {

    private final IgnitionProperties props;
    private final ZoneRepository zones;

    public TokenResolver(IgnitionProperties props, ZoneRepository zones) {
        this.props = props;
        this.zones = zones;
    }

    public Optional<IgnitionPrincipal> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String admin = props.getAdminToken();
        if (!admin.isBlank() && constantEquals(token, admin)) {
            return Optional.of(new IgnitionPrincipal(IgnitionPrincipal.Kind.PLATFORM, null));
        }
        for (Zone z : zones.findAll()) {
            if (constantEquals(token, zones.secret(z.slug(), "zone-token"))) {
                return Optional.of(new IgnitionPrincipal(IgnitionPrincipal.Kind.ZONE, z.slug()));
            }
            if (constantEquals(token, zones.secret(z.slug(), "deploy-token"))) {
                return Optional.of(new IgnitionPrincipal(IgnitionPrincipal.Kind.DEPLOY, z.slug()));
            }
        }
        return Optional.empty();
    }

    private static boolean constantEquals(String a, String b) {
        if (b == null || b.isEmpty()) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
