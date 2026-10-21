package com.hypeman.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** A refresh token, stored only as the SHA-256 of what the app holds. */
@Entity
@Table(name = "refresh_tokens")
class RefreshToken {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID userId;

    private UUID deviceId;

    private byte[] tokenHash;

    private Instant expiresAt;

    private Instant revokedAt;

    protected RefreshToken() {
    }

    RefreshToken(UUID userId, UUID deviceId, byte[] tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.deviceId = deviceId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    UUID getId() {
        return id;
    }

    UUID getUserId() {
        return userId;
    }

    UUID getDeviceId() {
        return deviceId;
    }

    boolean isRevoked() {
        return revokedAt != null;
    }

    boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    void revoke(Instant now) {
        revokedAt = now;
    }
}
