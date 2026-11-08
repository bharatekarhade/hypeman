package com.hypeman.goal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A user's goal as the server last heard it from any of their devices. A deleted goal stays as a tombstone with only
 * its ids and times, so devices that haven't heard yet learn it's gone, and it stays deleted.
 */
@Entity
@Table(name = "goals")
public class Goal {

    @Id
    private UUID id;

    private UUID userId;

    private UUID goalId;

    private String title;

    private Integer sortIndex;

    private Instant reminderAt;

    private Instant startedAt;

    private Instant completedAt;

    private String motivatorId;

    private Instant changedAt;

    private Instant deletedAt;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    /** Kept current by the database's set_updated_at trigger. */
    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected Goal() {
    }

    public Goal(UUID id, UUID userId, UUID goalId) {
        this.id = id;
        this.userId = userId;
        this.goalId = goalId;
    }

    /**
     * Takes the change if it's newer than what's stored. A deletion clears everything but the ids and times, and nothing
     * brings a deleted goal back.
     *
     * @return whether anything changed
     */
    public boolean apply(GoalChange change) {
        if (isDeleted() || (changedAt != null && !change.changedAt().isAfter(changedAt))) {
            return false;
        }
        changedAt = change.changedAt();
        if (change.deleted()) {
            deletedAt = change.changedAt();
            title = null;
            sortIndex = null;
            reminderAt = null;
            startedAt = null;
            completedAt = null;
            motivatorId = null;
            return true;
        }
        title = change.title();
        sortIndex = change.sortIndex();
        reminderAt = change.reminderAt();
        startedAt = change.startedAt();
        completedAt = change.completedAt();
        motivatorId = change.motivatorId();
        return true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getGoalId() {
        return goalId;
    }

    public String getTitle() {
        return title;
    }

    public Integer getSortIndex() {
        return sortIndex;
    }

    public Instant getReminderAt() {
        return reminderAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getMotivatorId() {
        return motivatorId;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
