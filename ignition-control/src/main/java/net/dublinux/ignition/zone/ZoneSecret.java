package net.dublinux.ignition.zone;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** One credential for a zone ({@code runner-secret}, {@code deploy-token}, …). Value is AES-GCM ciphertext. */
@Entity
@Table(name = "zone_secret")
@IdClass(ZoneSecret.Key.class)
public class ZoneSecret {

    @Id
    @Column(name = "zone_slug")
    private String zoneSlug;

    @Id
    private String name;

    @Column(nullable = false)
    private String value;

    protected ZoneSecret() {
    }

    public ZoneSecret(String zoneSlug, String name, String value) {
        this.zoneSlug = zoneSlug;
        this.name = name;
        this.value = value;
    }

    public String zoneSlug() { return zoneSlug; }
    public String name() { return name; }
    public String value() { return value; }
    public void setValue(String value) { this.value = value; }

    public static class Key implements Serializable {
        private String zoneSlug;
        private String name;

        public Key() {
        }

        public Key(String zoneSlug, String name) {
            this.zoneSlug = zoneSlug;
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key k)) {
                return false;
            }
            return Objects.equals(zoneSlug, k.zoneSlug) && Objects.equals(name, k.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(zoneSlug, name);
        }
    }
}
