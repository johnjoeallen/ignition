package net.dublinux.ignition.auth;

import java.util.ArrayList;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

/** Granted authorities for a user: {@code PLATFORM_ADMIN} + per-zone role authorities. */
@Service
public class AuthorityService {

    private final ZoneMemberRepository members;

    public AuthorityService(ZoneMemberRepository members) {
        this.members = members;
    }

    public List<GrantedAuthority> forUser(AppUser u) {
        List<GrantedAuthority> out = new ArrayList<>();
        if (u.isPlatformAdmin()) {
            out.add(new SimpleGrantedAuthority("PLATFORM_ADMIN"));
        }
        for (ZoneMember m : members.findByUserId(u.id())) {
            out.add(new SimpleGrantedAuthority(m.role().name() + ":" + m.zoneSlug()));
            if (m.role() == ZoneMember.Role.ZONE_ADMIN) {
                out.add(new SimpleGrantedAuthority("MEMBER:" + m.zoneSlug()));
            }
        }
        return out;
    }
}
