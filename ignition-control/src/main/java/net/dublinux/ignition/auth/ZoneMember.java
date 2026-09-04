package net.dublinux.ignition.auth;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** A user's membership of one zone. Row in {@code zone_member}. */
@Entity
@Table(name = "zone_member")
@IdClass(ZoneMember.Key.class)
public class ZoneMember {

    public enum Role { MEMBER, ZONE_ADMIN }

    @Id
    @Column(name = "zone_slug")
    private String zoneSlug;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt = Instant.now();

    protected ZoneMember() {
    }

    public ZoneMember(String zoneSlug, UUID userId, Role role) {
        this.zoneSlug = zoneSlug;
        this.userId = userId;
        this.role = role;
    }

    public String zoneSlug() { return zoneSlug; }
    public UUID userId() { return userId; }
    public Role role() { return role; }
    public void setRole(Role role) { this.role = role; }

    public static class Key implements Serializable {
        private String zoneSlug;
        private UUID userId;

        public Key() {
        }

        public Key(String zoneSlug, UUID userId) {
            this.zoneSlug = zoneSlug;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key k)) {
                return false;
            }
            return Objects.equals(zoneSlug, k.zoneSlug) && Objects.equals(userId, k.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(zoneSlug, userId);
        }
    }
}
