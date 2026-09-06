package net.dublinux.ignition.app;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * An app's "dev" deployment — the latest {@code main} HEAD at
 * {@code <name>.dev.<zone>.<BASE_DOMAIN>}. Written when "Deploy from main"
 * succeeds, removed on stop / app delete / zone destroy. Reuses
 * {@link DeployedApp.Key} (zone + name) as its id.
 */
@Entity
@Table(name = "app_dev")
@IdClass(DeployedApp.Key.class)
public class DevDeployment {

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

    protected DevDeployment() {
    }

    public DevDeployment(String zone, String name, String node, String image, int port, String deployId) {
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
    public Instant deployedAt() { return deployedAt; }

    public void update(String node, String image, int port, String deployId) {
        this.node = node;
        this.image = image;
        this.port = port;
        this.deployId = deployId;
        this.deployedAt = Instant.now();
    }

    /** {@code https://<name>.dev.<zone>.<baseDomain>/} */
    public String url(String baseDomain) {
        return "https://%s.dev.%s.%s/".formatted(name, zone, baseDomain);
    }
}
