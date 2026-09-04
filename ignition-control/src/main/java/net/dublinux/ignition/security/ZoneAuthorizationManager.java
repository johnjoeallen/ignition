package net.dublinux.ignition.security;

import java.util.function.Supplier;

import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Gates the team console + its actions, {@code /teams/{slug}/...} — scoped by
 * the {@code {slug}} path variable on the matched request pattern, not a
 * plain {@code hasAuthority(...)} matcher's static path. A platform admin
 * gets in everywhere; anyone else needs a {@code MEMBER:<slug>} authority for
 * the team named in the request, which {@link net.dublinux.ignition.auth.AuthorityService}
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
        String slug = ctx.getVariables().get("slug");
        if (slug == null || slug.isBlank()) {
            return new AuthorizationDecision(false);
        }
        boolean member = authentication.get().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("MEMBER:" + slug));
        return new AuthorizationDecision(member);
    }
}
