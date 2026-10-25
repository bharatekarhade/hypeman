package com.hypeman.yell.model;

import com.hypeman.pack.model.ClipRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A clip a user recorded for their friends: audio, maybe video, and its line as a caption. */
@Entity
@Table(name = "yells")
public class Yell {

    @Id
    private UUID id;

    private UUID senderId;

    @Enumerated(EnumType.STRING)
    private ClipRole role;

    private String caption;

    private String audioKey;

    private String videoKey;

    private Integer durationMs;

    @Enumerated(EnumType.STRING)
    private YellStatus status;

    private Instant sentAt;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected Yell() {
    }

    public Yell(UUID id, UUID senderId, ClipRole role, String caption, String audioKey, String videoKey) {
        this.id = id;
        this.senderId = senderId;
        this.role = role;
        this.caption = caption;
        this.audioKey = audioKey;
        this.videoKey = videoKey;
        this.status = YellStatus.UPLOADING;
    }

    public void markSent(Instant now) {
        status = YellStatus.READY;
        sentAt = now;
    }

    public void recordDuration(int durationMs) {
        this.durationMs = durationMs;
    }

    public void remove() {
        status = YellStatus.REMOVED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public ClipRole getRole() {
        return role;
    }

    public String getCaption() {
        return caption;
    }

    public String getAudioKey() {
        return audioKey;
    }

    public String getVideoKey() {
        return videoKey;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public YellStatus getStatus() {
        return status;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
