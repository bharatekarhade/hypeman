package com.hypeman.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    private String apnsToken;

    @Enumerated(EnumType.STRING)
    private ApnsEnvironment environment;

    private Instant lastSeenAt;

    protected Device() {
    }

    Device(UUID id, UUID userId, Instant now) {
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

    public String getApnsToken() {
        return apnsToken;
    }

    public ApnsEnvironment getEnvironment() {
        return environment;
    }

    /** Someone else signed in on this phone: their pushes go here from now on, the previous user's don't. */
    void moveTo(UUID newUserId) {
        userId = newUserId;
        clearPushToken();
    }

    void seen(Instant now) {
        lastSeenAt = now;
    }

    void setPushToken(String token, ApnsEnvironment env) {
        apnsToken = token;
        environment = env;
    }

    void clearPushToken() {
        apnsToken = null;
        environment = null;
    }

    public enum ApnsEnvironment {
        SANDBOX,
        PRODUCTION
    }
}
