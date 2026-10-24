package com.hypeman.purchase.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A pack someone bought on its own. A refund keeps the row and sets revokedAt, so buying again reuses it. */
@Entity
@Table(name = "pack_purchases")
public class PackPurchase {

    @Id
    private UUID id;

    private UUID userId;

    private UUID packId;

    private String originalTransactionId;

    private String environment;

    private Instant purchasedAt;

    private Instant revokedAt;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected PackPurchase() {
    }

    public PackPurchase(UUID id, UUID userId, UUID packId) {
        this.id = id;
        this.userId = userId;
        this.packId = packId;
    }

    /**
     * Records what a transaction says about this pack. Copies arrive late and out of order, and one signed before a refund
     * stays valid, so a refund is never undone and an earlier purchase never replaces a later one (bought again after a
     * refund).
     */
    public void apply(VerifiedTransaction transaction) {
        if (transaction.originalTransactionId().equals(originalTransactionId)) {
            if (revokedAt == null) {
                revokedAt = transaction.revokedAt();
            }
            return;
        }
        if (purchasedAt != null && !transaction.purchasedAt().isAfter(purchasedAt)) {
            return;
        }
        originalTransactionId = transaction.originalTransactionId();
        environment = transaction.environment();
        purchasedAt = transaction.purchasedAt();
        revokedAt = transaction.revokedAt();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getPackId() {
        return packId;
    }

    public String getOriginalTransactionId() {
        return originalTransactionId;
    }

    public String getEnvironment() {
        return environment;
    }

    public Instant getPurchasedAt() {
        return purchasedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public boolean isActive() {
        return revokedAt == null;
    }
}
