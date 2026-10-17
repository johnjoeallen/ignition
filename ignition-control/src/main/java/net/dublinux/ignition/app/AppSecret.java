package net.dublinux.ignition.app;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** One encrypted runtime secret of an app. Row in {@code app_secret}. */
@Entity
@Table(name = "app_secret")
@IdClass(AppSecret.Key.class)
public class AppSecret {

    @Id
    @Column(name = "zone_slug")
    private String zone;

    @Id
    private String app;

    @Id
    private String scope;

    @Id
    private String name;

    /** AES-GCM ciphertext, base64 — see {@code SecretCipher}. */
    @Column(nullable = false)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by")
    private String updatedBy;

    protected AppSecret() {
    }

    public AppSecret(String zone, String app, SecretScope scope, String name, String value, String updatedBy) {
        this.zone = zone;
        this.app = app;
        this.scope = scope.key();
        this.name = name;
        set(value, updatedBy);
    }

    public String zone() { return zone; }
    public String app() { return app; }
    public String scope() { return scope; }
    public String name() { return name; }
    public String value() { return value; }
    public Instant updatedAt() { return updatedAt; }
    public String updatedBy() { return updatedBy; }

    public void set(String value, String updatedBy) {
        this.value = value;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public static class Key implements Serializable {
        private String zone;
        private String app;
        private String scope;
        private String name;

        public Key() {
        }

        public Key(String zone, String app, SecretScope scope, String name) {
            this.zone = zone;
            this.app = app;
            this.scope = scope.key();
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(zone, k.zone)
                    && Objects.equals(app, k.app) && Objects.equals(scope, k.scope)
                    && Objects.equals(name, k.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(zone, app, scope, name);
        }
    }
}
