package com.hypeman.friend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** The blocker no longer sees the blocked user's yells, and the two can't become friends again. */
@Entity
@Table(name = "blocks")
@IdClass(Block.Key.class)
public class Block {

    @Id
    private UUID blockerId;

    @Id
    private UUID blockedId;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected Block() {
    }

    public Block(UUID blockerId, UUID blockedId) {
        this.blockerId = blockerId;
        this.blockedId = blockedId;
    }

    public UUID getBlockerId() {
        return blockerId;
    }

    public UUID getBlockedId() {
        return blockedId;
    }

    public record Key(UUID blockerId, UUID blockedId) implements Serializable {
    }
}
