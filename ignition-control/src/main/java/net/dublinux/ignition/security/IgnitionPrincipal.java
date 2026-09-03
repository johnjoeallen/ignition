package net.dublinux.ignition.security;

/**
 * Who is calling, resolved from the bearer token / session.
 *
 * <ul>
 *   <li>{@code PLATFORM} — holds {@code ignition.admin-token}; {@code slug} is null.</li>
 *   <li>{@code ZONE} — holds a zone's {@code zone-token}; {@code slug} is that zone.</li>
 *   <li>{@code DEPLOY} — holds a zone's {@code deploy-token}; CI only.</li>
 * </ul>
 */
public record IgnitionPrincipal(Kind kind, String slug) {

    public enum Kind { PLATFORM, ZONE, DEPLOY }

    public String role() {
        return "ROLE_" + kind.name();
    }

    @Override
    public String toString() {
        return kind == Kind.PLATFORM ? "platform" : kind.name().toLowerCase() + ":" + slug;
    }
}
