package com.hypeman.friend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A code a user shares so someone can add them as a friend. It works once, until it expires. */
@Entity
@Table(name = "friend_invites")
public class FriendInvite {

    @Id
    private UUID id;

    private UUID inviterId;

    private String code;

    private Instant expiresAt;

    private UUID acceptedBy;

    private Instant acceptedAt;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected FriendInvite() {
    }

    public FriendInvite(UUID id, UUID inviterId, String code, Instant expiresAt) {
        this.id = id;
        this.inviterId = inviterId;
        this.code = code;
        this.expiresAt = expiresAt;
    }

    public void accept(UUID userId, Instant now) {
        acceptedBy = userId;
        acceptedAt = now;
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getInviterId() {
        return inviterId;
    }

    public String getCode() {
        return code;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getAcceptedBy() {
        return acceptedBy;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }
}
