package net.dublinux.ignition.security;

import java.util.function.Supplier;

import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Gates {@code /z/**} (the team console — every action there is scoped by the
 * {@code ?z=<slug>} request param, not a path variable, so a plain
 * {@code hasAuthority(...)} matcher can't express it). A platform admin gets
 * in everywhere; anyone else needs a {@code MEMBER:<slug>} authority for the
 * zone named in the request, which {@link net.dublinux.ignition.auth.AuthorityService}
 * grants to both team roles (a {@code ZONE_ADMIN:<slug>} always also carries
 * {@code MEMBER:<slug>}).
 */
@Component
public class ZoneAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final AuthorizationManager<Object> platformAdmin = AuthorityAuthorizationManager.hasAuthority("PLATFORM_ADMIN");

    @Override
    public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication, RequestAuthorizationContext ctx) {
        if (platformAdmin.authorize(authentication, null).isGranted()) {
            return new AuthorizationDecision(true);
        }
        String slug = ctx.getRequest().getParameter("z");
        if (slug == null || slug.isBlank()) {
            return new AuthorizationDecision(false);
        }
        boolean member = authentication.get().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("MEMBER:" + slug));
        return new AuthorizationDecision(member);
    }
}
