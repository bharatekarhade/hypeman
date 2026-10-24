package com.hypeman.purchase.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One Pro subscription period. Apple can send the same period any number of times, late and out of order, and a signed
 * copy from before a refund stays valid, so applying one only ever adds what it knows: a refund is never undone and an
 * end date only moves later.
 */
@Entity
@Table(name = "pro_transactions")
public class ProTransaction {

    @Id
    private UUID id;

    private UUID userId;

    private String transactionId;

    private String originalTransactionId;

    private String productId;

    private String environment;

    private Instant purchasedAt;

    private Instant expiresAt;

    private Instant revokedAt;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected ProTransaction() {
    }

    public ProTransaction(UUID id, UUID userId, String transactionId) {
        this.id = id;
        this.userId = userId;
        this.transactionId = transactionId;
    }

    public void apply(VerifiedTransaction transaction) {
        originalTransactionId = transaction.originalTransactionId();
        productId = transaction.productId();
        environment = transaction.environment();
        purchasedAt = transaction.purchasedAt();
        if (expiresAt == null || transaction.expiresAt().isAfter(expiresAt)) {
            expiresAt = transaction.expiresAt();
        }
        if (revokedAt == null) {
            revokedAt = transaction.revokedAt();
        }
    }

    /** When this period's Pro ends: its end date, or the refund if that came first. */
    public Instant getAccessUntil() {
        return revokedAt != null && revokedAt.isBefore(expiresAt) ? revokedAt : expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getOriginalTransactionId() {
        return originalTransactionId;
    }

    public String getProductId() {
        return productId;
    }

    public String getEnvironment() {
        return environment;
    }

    public Instant getPurchasedAt() {
        return purchasedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
