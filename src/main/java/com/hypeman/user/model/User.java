package com.hypeman.user.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "users")
public class User {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String displayName;

    private Instant bannedAt;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    /** Kept current by the database's set_updated_at trigger. */
    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isBanned() {
        return bannedAt != null;
    }

    public Instant getBannedAt() {
        return bannedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void rename(String displayName) {
        this.displayName = displayName;
    }

    public void ban(Instant at) {
        bannedAt = at;
    }

    public void unban() {
        bannedAt = null;
    }
}
