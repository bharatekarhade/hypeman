package com.hypeman.pack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One pack's clip for one role. The uploader's original sits at sourceKey; processing fills in the video, audio, alarm
 * sound and thumbnail, or sets FAILED with a reason.
 */
@Entity
@Table(name = "clips")
public class Clip {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID packId;

    @Enumerated(EnumType.STRING)
    private ClipRole role;

    private String caption;

    private String sourceKey;

    private String videoKey;

    private String audioKey;

    private String alarmKey;

    private String thumbKey;

    private Integer durationMs;

    @Enumerated(EnumType.STRING)
    private ClipStatus status = ClipStatus.UPLOADING;

    private String failureReason;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected Clip() {
    }

    /** @param caption the spoken line, shown on screen while the clip plays */
    public Clip(UUID packId, ClipRole role, String caption) {
        this.packId = packId;
        this.role = role;
        this.caption = caption;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPackId() {
        return packId;
    }

    public ClipRole getRole() {
        return role;
    }

    public String getCaption() {
        return caption;
    }

    public void updateCaption(String caption) {
        this.caption = caption;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public String getVideoKey() {
        return videoKey;
    }

    public String getAudioKey() {
        return audioKey;
    }

    public String getAlarmKey() {
        return alarmKey;
    }

    public String getThumbKey() {
        return thumbKey;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public ClipStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** A new original is on its way to sourceKey; anything processed from the previous one no longer applies. */
    public void uploadStarted(String sourceKey) {
        this.sourceKey = sourceKey;
        this.videoKey = null;
        this.audioKey = null;
        this.alarmKey = null;
        this.thumbKey = null;
        this.durationMs = null;
        this.status = ClipStatus.UPLOADING;
        this.failureReason = null;
    }

    public void markProcessing() {
        this.status = ClipStatus.PROCESSING;
    }

    public void markReady(String videoKey, String audioKey, String alarmKey, String thumbKey, Integer durationMs) {
        this.videoKey = videoKey;
        this.audioKey = audioKey;
        this.alarmKey = alarmKey;
        this.thumbKey = thumbKey;
        this.durationMs = durationMs;
        this.status = ClipStatus.READY;
    }
}
