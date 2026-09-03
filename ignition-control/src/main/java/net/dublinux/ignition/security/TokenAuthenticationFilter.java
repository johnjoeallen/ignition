package net.dublinux.ignition.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads a token from {@code Authorization: Bearer}, the {@code ign_token}
 * session attribute, or the {@code ign_token} cookie, resolves it to an
 * {@link IgnitionPrincipal}, and populates the security context.
 */
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    public static final String SESSION_KEY = "ign_token";
    public static final String COOKIE_NAME = "ign_token";

    private final TokenResolver resolver;

    public TokenAuthenticationFilter(TokenResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = tokenFrom(request);
            resolver.resolve(token).ifPresent(principal -> {
                var auth = new PrincipalAuthentication(principal);
                auth.setAuthenticated(true);
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(request, response);
    }

    private static String tokenFrom(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).strip();
        }
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SESSION_KEY) instanceof String s) {
            return s;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (COOKIE_NAME.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    /** Thin {@link org.springframework.security.core.Authentication} carrying the principal. */
    public static final class PrincipalAuthentication extends AbstractAuthenticationToken {
        private final IgnitionPrincipal principal;

        public PrincipalAuthentication(IgnitionPrincipal principal) {
            super(List.of(new SimpleGrantedAuthority(principal.role())));
            this.principal = principal;
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public IgnitionPrincipal getPrincipal() {
            return principal;
        }
    }
}
