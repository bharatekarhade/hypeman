package com.hypeman.yell.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A recipient flagged a yell. It waits in the moderation queue while OPEN. */
@Entity
@Table(name = "reports")
public class Report {

    @Id
    private UUID id;

    private UUID reporterId;

    private UUID yellId;

    @Enumerated(EnumType.STRING)
    private ReportReason reason;

    @Enumerated(EnumType.STRING)
    private ReportStatus status;

    private Instant handledAt;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected Report() {
    }

    public Report(UUID id, UUID reporterId, UUID yellId, ReportReason reason) {
        this.id = id;
        this.reporterId = reporterId;
        this.yellId = yellId;
        this.reason = reason;
        this.status = ReportStatus.OPEN;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReporterId() {
        return reporterId;
    }

    public UUID getYellId() {
        return yellId;
    }

    public ReportReason getReason() {
        return reason;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
