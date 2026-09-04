package net.dublinux.ignition.zone;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One team's isolated stack, assigned 1:1 to a node. Row in {@code zone}. */
@Entity
@Table(name = "zone")
public class Zone {

    public enum Visibility { PUBLIC, PRIVATE }

    @Id
    private String slug;

    @Column(name = "node_name", nullable = false)
    private String node;

    @Column(name = "base_domain", nullable = false)
    private String baseDomain;

    @Column(name = "zone_cpus", nullable = false)
    private double zoneCpus;

    @Column(name = "zone_mem_gb", nullable = false)
    private double zoneMemGb;

    @Column(name = "git_host", nullable = false)
    private String gitHost;

    @Column(name = "forgejo_url", nullable = false)
    private String forgejoUrl;

    @Column(name = "apps_base", nullable = false)
    private String appsBase;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Visibility visibility = Visibility.PUBLIC;

    @Column(name = "last_activity", nullable = false)
    private Instant lastActivity = Instant.now();

    @Column(name = "created_by")
    private java.util.UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Zone() {
    }

    public Zone(String slug, String node, String baseDomain, double zoneCpus, double zoneMemGb,
               String gitHost, String forgejoUrl, String appsBase) {
        this.slug = slug;
        this.node = node;
        this.baseDomain = baseDomain;
        this.zoneCpus = zoneCpus;
        this.zoneMemGb = zoneMemGb;
        this.gitHost = gitHost;
        this.forgejoUrl = forgejoUrl;
        this.appsBase = appsBase;
    }

    public String slug() { return slug; }
    public String node() { return node; }
    public String baseDomain() { return baseDomain; }
    public double zoneCpus() { return zoneCpus; }
    public double zoneMemGb() { return zoneMemGb; }
    public String gitHost() { return gitHost; }
    public String forgejoUrl() { return forgejoUrl; }
    public String appsBase() { return appsBase; }
    public Visibility visibility() { return visibility; }
    public Instant lastActivity() { return lastActivity; }

    public java.util.UUID createdBy() { return createdBy; }

    public void setNode(String node) { this.node = node; }
    public void setVisibility(Visibility visibility) { this.visibility = visibility; }
    public void setCreatedBy(java.util.UUID createdBy) { this.createdBy = createdBy; }
    public void touch() { this.lastActivity = Instant.now(); }
}
