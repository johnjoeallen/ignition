package net.dublinux.ignition.auth;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The signed-in {@link AppUser}, and role checks against their granted authorities. */
@Component
public class CurrentUser {

    private final AppUserRepository users;

    public CurrentUser(AppUserRepository users) {
        this.users = users;
    }

    public Optional<AppUser> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return Optional.empty();
        }
        return users.findByEmailIgnoreCase(auth.getName());
    }

    /** A platform admin is implicitly an admin of every zone. */
    public boolean isPlatformAdmin() {
        return has("PLATFORM_ADMIN");
    }

    public boolean isZoneAdmin(String slug) {
        return isPlatformAdmin() || has("ZONE_ADMIN:" + slug);
    }

    public boolean isZoneMember(String slug) {
        return isPlatformAdmin() || has("MEMBER:" + slug);
    }

    private boolean has(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(authority));
    }
}
