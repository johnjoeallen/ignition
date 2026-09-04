package net.dublinux.ignition.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A person with an Ignition login. Row in {@code app_user}. */
@Entity
@Table(name = "app_user")
public class AppUser {

    public enum Status { PENDING_VERIFICATION, PENDING_APPROVAL, ACTIVE, DISABLED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "is_platform_admin", nullable = false)
    private boolean platformAdmin;

    /** Admin-invited: activation goes straight to ACTIVE, no approval step. */
    @Column(nullable = false)
    private boolean preapproved;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "activated_at")
    private Instant activatedAt;

    protected AppUser() {
    }

    public AppUser(String email, Status status, boolean platformAdmin, boolean preapproved) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.status = status;
        this.platformAdmin = platformAdmin;
        this.preapproved = preapproved;
    }

    public UUID id() { return id; }
    public String email() { return email; }
    public String passwordHash() { return passwordHash; }
    public Status status() { return status; }
    public boolean isPlatformAdmin() { return platformAdmin; }
    public boolean isPreapproved() { return preapproved; }
    public Instant activatedAt() { return activatedAt; }

    public void setStatus(Status status) { this.status = status; }
    public void setPlatformAdmin(boolean platformAdmin) { this.platformAdmin = platformAdmin; }
    public void setPreapproved(boolean preapproved) { this.preapproved = preapproved; }

    /**
     * Set the (already-encoded) password after email verification. An
     * admin-invited (preapproved) account goes straight to ACTIVE; a
     * self-signup lands in PENDING_APPROVAL.
     */
    public void activate(String encodedPassword) {
        this.passwordHash = encodedPassword;
        this.activatedAt = Instant.now();
        if (status == Status.PENDING_VERIFICATION) {
            this.status = preapproved ? Status.ACTIVE : Status.PENDING_APPROVAL;
        }
    }

    public void setPasswordHash(String encodedPassword) {
        this.passwordHash = encodedPassword;
    }
}
