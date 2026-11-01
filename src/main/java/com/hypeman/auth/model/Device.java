package com.hypeman.auth.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An install of the app. The id is the device id the app generates and sends at sign-in. */
@Entity
@Table(name = "devices")
public class Device {

    @Id
    private UUID id;

    private UUID userId;

    private Instant lastSeenAt;

    protected Device() {
    }

    public Device(UUID id, UUID userId, Instant now) {
        this.id = id;
        this.userId = userId;
        this.lastSeenAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    /** Someone else signed in on this phone. */
    public void moveTo(UUID newUserId) {
        userId = newUserId;
    }

    public void seen(Instant now) {
        lastSeenAt = now;
    }
}
