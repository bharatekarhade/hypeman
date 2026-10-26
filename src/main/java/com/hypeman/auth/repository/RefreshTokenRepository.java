package com.hypeman.auth.repository;

import com.hypeman.auth.model.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(byte[] tokenHash);

    @Modifying(flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.deviceId = :deviceId and t.revokedAt is null")
    int revokeAllForDevice(UUID deviceId, Instant now);

    @Modifying(flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(UUID userId, Instant now);

    /**
     * Revokes the token only if nobody has yet: 1 if this call did, 0 if another got there first. Postgres makes a
     * concurrent second update wait for the first to commit, then finds revoked_at set, so exactly one caller wins.
     */
    @Modifying(flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.id = :id and t.revokedAt is null")
    int revokeIfActive(UUID id, Instant now);
}
