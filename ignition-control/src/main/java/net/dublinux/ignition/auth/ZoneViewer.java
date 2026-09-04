package net.dublinux.ignition.auth;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** An email allow-listed to view a PRIVATE zone. Row in {@code zone_viewer}. */
@Entity
@Table(name = "zone_viewer")
@IdClass(ZoneViewer.Key.class)
public class ZoneViewer {

    @Id
    @Column(name = "zone_slug")
    private String zoneSlug;

    @Id
    private String email;

    @Column(name = "added_by")
    private UUID addedBy;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt = Instant.now();

    protected ZoneViewer() {
    }

    public ZoneViewer(String zoneSlug, String email, UUID addedBy) {
        this.zoneSlug = zoneSlug;
        this.email = email;
        this.addedBy = addedBy;
    }

    public String zoneSlug() { return zoneSlug; }
    public String email() { return email; }

    public static class Key implements Serializable {
        private String zoneSlug;
        private String email;

        public Key() {
        }

        public Key(String zoneSlug, String email) {
            this.zoneSlug = zoneSlug;
            this.email = email;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key k)) {
                return false;
            }
            return Objects.equals(zoneSlug, k.zoneSlug) && Objects.equals(email, k.email);
        }

        @Override
        public int hashCode() {
            return Objects.hash(zoneSlug, email);
        }
    }
}
