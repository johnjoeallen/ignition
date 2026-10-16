package net.dublinux.ignition.app;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Settings for an app by (zone, name), independent of any deployment — so they
 * can be set before the first release. Row in {@code app_config}.
 */
@Entity
@Table(name = "app_config")
@IdClass(DeployedApp.Key.class)
public class AppConfig {

    @Id
    @Column(name = "zone_slug")
    private String zone;

    @Id
    private String name;

    /** Minutes of no traffic before the idle sweep stops the app; null = platform default, 0 = never. */
    @Column(name = "idle_ttl_minutes")
    private Integer idleTtlMinutes;

    protected AppConfig() {
    }

    public AppConfig(String zone, String name, Integer idleTtlMinutes) {
        this.zone = zone;
        this.name = name;
        this.idleTtlMinutes = idleTtlMinutes;
    }

    public String zone() { return zone; }
    public String name() { return name; }
    public Integer idleTtlMinutes() { return idleTtlMinutes; }
    public void setIdleTtlMinutes(Integer minutes) { this.idleTtlMinutes = minutes; }
}
