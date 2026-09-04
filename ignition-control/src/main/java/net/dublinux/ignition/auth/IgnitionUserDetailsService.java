package net.dublinux.ignition.auth;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Loads an {@link AppUser} by email for form login. Only ACTIVE users can sign in. */
@Service
public class IgnitionUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;
    private final AuthorityService authorities;

    public IgnitionUserDetailsService(AppUserRepository users, AuthorityService authorities) {
        this.users = users;
        this.authorities = authorities;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        AppUser u = users.findByEmailIgnoreCase(email.strip())
                .orElseThrow(() -> new UsernameNotFoundException("no such account"));
        boolean enabled = u.status() == AppUser.Status.ACTIVE;
        return User.withUsername(u.email())
                .password(u.passwordHash() == null ? "{noop}\0" : u.passwordHash())
                .authorities(authorities.forUser(u))
                .disabled(!enabled)
                .accountLocked(u.status() == AppUser.Status.DISABLED)
                .build();
    }
}
