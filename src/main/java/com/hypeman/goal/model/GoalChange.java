package com.hypeman.goal.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One goal as the app last saved it, or its deletion.
 *
 * @param goalId    the app's id for the goal
 * @param changedAt when the app made the change; the newest change wins
 * @param deleted   true when the goal was deleted; the other fields are then ignored
 */
public record GoalChange(UUID goalId, String title, Integer sortIndex, Instant reminderAt, Instant startedAt,
                         Instant completedAt, String motivatorId, Instant changedAt, boolean deleted) {
}
