package net.dublinux.ignition.app;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** One deployed app, unique within its zone. Row in {@code app}. */
@Entity
@Table(name = "app")
@IdClass(DeployedApp.Key.class)
public class DeployedApp {

    @Id
    @Column(name = "zone_slug")
    private String zone;

    @Id
    private String name;

    @Column(name = "node_name", nullable = false)
    private String node;

    @Column(nullable = false)
    private String image;

    private int port;

    @Column(name = "deploy_id", nullable = false)
    private String deployId;

    @Column(name = "deployed_at", nullable = false)
    private Instant deployedAt = Instant.now();

    protected DeployedApp() {
    }

    public DeployedApp(String zone, String name, String node, String image, int port, String deployId) {
        this.zone = zone;
        this.name = name;
        this.node = node;
        this.image = image;
        this.port = port;
        this.deployId = deployId;
    }

    public String zone() { return zone; }
    public String name() { return name; }
    public String node() { return node; }
    public String image() { return image; }
    public int port() { return port; }
    public String deployId() { return deployId; }

    public void update(String node, String image, int port, String deployId) {
        this.node = node;
        this.image = image;
        this.port = port;
        this.deployId = deployId;
        this.deployedAt = Instant.now();
    }

    /** {@code <name>.apps.<zone>.<baseDomain>} */
    public String url(String baseDomain) {
        return "https://%s.apps.%s.%s/".formatted(name, zone, baseDomain);
    }

    public static class Key implements Serializable {
        private String zone;
        private String name;

        public Key() {
        }

        public Key(String zone, String name) {
            this.zone = zone;
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key k)) {
                return false;
            }
            return Objects.equals(zone, k.zone) && Objects.equals(name, k.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(zone, name);
        }
    }
}
