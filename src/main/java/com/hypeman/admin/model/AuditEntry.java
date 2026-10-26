package com.hypeman.admin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One admin action, kept for good. */
@Entity
@Table(name = "audit_log")
public class AuditEntry {

    @Id
    private UUID id;

    private String actor;

    private String action;

    private String target;

    private String detail;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    protected AuditEntry() {
    }

    public AuditEntry(UUID id, String actor, String action, String target, String detail) {
        this.id = id;
        this.actor = actor;
        this.action = action;
        this.target = target;
        this.detail = detail;
    }

    public UUID getId() {
        return id;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getTarget() {
        return target;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
