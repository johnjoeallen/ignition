package net.dublinux.ignition.auth;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who can reach a team's console, and at what role. Deliberately <b>not</b> a
 * signup path — "add a member" attaches an existing, already-registered
 * {@link AppUser} to the zone; it never creates one. (Onboarding a brand-new
 * person is {@code /signup} + platform-admin approval, or a platform admin's
 * invite — separate from team membership.)
 */
@Service
public class ZoneAccessService {

    private final AppUserRepository users;
    private final ZoneMemberRepository members;

    public ZoneAccessService(AppUserRepository users, ZoneMemberRepository members) {
        this.users = users;
        this.members = members;
    }

    /** A member row joined with the account it points at, for display. */
    public record MemberView(UUID userId, String email, ZoneMember.Role role, boolean platformAdmin) {}

    public java.util.Optional<String> emailOf(UUID userId) {
        return users.findById(userId).map(AppUser::email);
    }

    /** The zones a user belongs to, and their role in each — for a non-platform-admin's landing page. */
    public record MyZone(String slug, ZoneMember.Role role) {}

    public List<MyZone> zonesFor(UUID userId) {
        return members.findByUserId(userId).stream()
                .map(m -> new MyZone(m.zoneSlug(), m.role()))
                .sorted(Comparator.comparing(MyZone::slug))
                .toList();
    }

    public List<MemberView> membersOf(String slug) {
        return members.findByZoneSlug(slug).stream()
                .map(m -> users.findById(m.userId())
                        .map(u -> new MemberView(u.id(), u.email(), m.role(), u.isPlatformAdmin()))
                        .orElse(null))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(MemberView::email))
                .toList();
    }

    /**
     * Attaches an existing account to the zone at the given role (or changes
     * their role, if they're already a member). Returns their user id — the
     * caller needs it right after anyway, to provision git access.
     */
    @Transactional
    public UUID addMember(String slug, String email, ZoneMember.Role role) {
        String e = email == null ? "" : email.strip().toLowerCase();
        AppUser u = users.findByEmailIgnoreCase(e)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no Ignition account for " + e + " — they need to sign up (or be invited) first"));
        ZoneMember m = members.findByZoneSlugAndUserId(slug, u.id())
                .orElseGet(() -> new ZoneMember(slug, u.id(), role));
        m.setRole(role);
        members.save(m);
        return u.id();
    }

    /**
     * @param actingUserId who's making the change. Demoting <em>yourself</em>
     *                     out of ZONE_ADMIN is refused outright, even with
     *                     other team admins around — same rule as platform
     *                     admin (see {@link AccountService#setPlatformAdmin}).
     *                     Unlike platform admin, there's no "last team admin"
     *                     guard beyond that — a team with zero admins isn't a
     *                     dead end, a platform admin can always add one back.
     */
    @Transactional
    public void setRole(String slug, UUID userId, ZoneMember.Role role, UUID actingUserId) {
        ZoneMember m = members.findByZoneSlugAndUserId(slug, userId)
                .orElseThrow(() -> new IllegalArgumentException("not a member of this team"));
        if (m.role() == ZoneMember.Role.ZONE_ADMIN && role != ZoneMember.Role.ZONE_ADMIN
                && userId.equals(actingUserId)) {
            throw new IllegalStateException(
                    "you can't demote yourself out of team admin — ask another team admin");
        }
        m.setRole(role);
        members.save(m);
    }

    /** No "last admin" guard here either — see {@link #setRole}. */
    @Transactional
    public void removeMember(String slug, UUID userId) {
        ZoneMember m = members.findByZoneSlugAndUserId(slug, userId)
                .orElseThrow(() -> new IllegalArgumentException("not a member of this team"));
        members.delete(m);
    }
}
