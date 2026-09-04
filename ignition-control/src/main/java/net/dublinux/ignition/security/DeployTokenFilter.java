package net.dublinux.ignition.security;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import net.dublinux.ignition.zone.Zone;
import net.dublinux.ignition.zone.ZoneRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The only remaining token auth: a {@code Bearer} deploy-token on
 * {@code POST /deploy} / {@code /undeploy}. Resolves to {@code ROLE_DEPLOY}
 * with the zone slug as the principal name. Humans use form login.
 */
@Component
public class DeployTokenFilter extends OncePerRequestFilter {

    private final ZoneRepository zones;

    public DeployTokenFilter(ZoneRepository zones) {
        this.zones = zones;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getServletPath();
        boolean target = "POST".equals(req.getMethod()) && ("/deploy".equals(path) || "/undeploy".equals(path));
        if (target && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = bearer(req);
            if (token != null) {
                for (Zone z : zones.findAll()) {
                    if (constantEquals(token, zones.secret(z.slug(), "deploy-token"))) {
                        var auth = new UsernamePasswordAuthenticationToken(
                                z.slug(), null, List.of(new SimpleGrantedAuthority("ROLE_DEPLOY")));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                        break;
                    }
                }
            }
        }
        chain.doFilter(req, res);
    }

    private static String bearer(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        return h != null && h.startsWith("Bearer ") ? h.substring(7).strip() : null;
    }

    private static boolean constantEquals(String a, String b) {
        if (a == null || b == null || b.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
