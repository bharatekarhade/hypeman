package com.hypeman.yell.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A yell in one recipient's inbox. Saving puts it in their library; hiding (or reporting) takes it out of the inbox. */
@Entity
@Table(name = "yell_deliveries")
public class YellDelivery {

    @Id
    private UUID id;

    private UUID yellId;

    private UUID recipientId;

    private Instant savedAt;

    private Instant hiddenAt;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected YellDelivery() {
    }

    public YellDelivery(UUID id, UUID yellId, UUID recipientId) {
        this.id = id;
        this.yellId = yellId;
        this.recipientId = recipientId;
    }

    public void save(Instant now) {
        if (savedAt == null) {
            savedAt = now;
        }
    }

    public void hide(Instant now) {
        if (hiddenAt == null) {
            hiddenAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getYellId() {
        return yellId;
    }

    public UUID getRecipientId() {
        return recipientId;
    }

    public Instant getSavedAt() {
        return savedAt;
    }

    public Instant getHiddenAt() {
        return hiddenAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
