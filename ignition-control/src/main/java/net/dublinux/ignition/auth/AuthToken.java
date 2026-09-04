package net.dublinux.ignition.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A single-use activation / reset token. Only the sha-256 of the raw token is stored. */
@Entity
@Table(name = "auth_token")
public class AuthToken {

    public enum Purpose { ACTIVATE, RESET }

    @Id
    @Column(name = "token_hash")
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Purpose purpose;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AuthToken() {
    }

    public AuthToken(String tokenHash, UUID userId, Purpose purpose, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.purpose = purpose;
        this.expiresAt = expiresAt;
    }

    public UUID userId() { return userId; }
    public Purpose purpose() { return purpose; }

    public boolean isUsable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public void markUsed() {
        this.usedAt = Instant.now();
    }
}
