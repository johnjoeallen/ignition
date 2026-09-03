package net.dublinux.ignition.web;

import net.dublinux.ignition.security.IgnitionPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;

/** Convenience access to the resolved {@link IgnitionPrincipal}. */
public final class CurrentPrincipal {

    private CurrentPrincipal() {}

    public static IgnitionPrincipal get() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof IgnitionPrincipal p ? p : null;
    }
}
