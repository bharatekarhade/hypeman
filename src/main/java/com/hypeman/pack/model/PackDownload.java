package com.hypeman.pack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Someone downloaded a pack's clips. The user is null once they delete their account; the download still counts. */
@Entity
@Table(name = "pack_downloads")
public class PackDownload {

    @Id
    private UUID id;

    private UUID packId;

    private UUID userId;

    @Column(insertable = false, updatable = false)
    private Instant downloadedAt;

    protected PackDownload() {
    }

    public PackDownload(UUID id, UUID packId, UUID userId) {
        this.id = id;
        this.packId = packId;
        this.userId = userId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPackId() {
        return packId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getDownloadedAt() {
        return downloadedAt;
    }
}
