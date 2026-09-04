package net.dublinux.ignition.provisioning;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import net.dublinux.ignition.provisioning.ProvisioningService.State;

/** Last known provisioning outcome for a zone slug. Row in {@code provisioning_status}. */
@Entity
@Table(name = "provisioning_status")
public class ProvisioningStatusEntity {

    @Id
    @Column(name = "zone_slug")
    private String zoneSlug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private State state;

    private String message;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ProvisioningStatusEntity() {
    }

    public ProvisioningStatusEntity(String zoneSlug, State state, String message) {
        this.zoneSlug = zoneSlug;
        this.state = state;
        this.message = message;
        this.updatedAt = Instant.now();
    }

    public String zoneSlug() { return zoneSlug; }
    public State state() { return state; }
    public String message() { return message; }
    public Instant updatedAt() { return updatedAt; }

    public void set(State state, String message) {
        this.state = state;
        this.message = message;
        this.updatedAt = Instant.now();
    }
}
