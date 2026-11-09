package com.hypeman.goal.service;

import com.hypeman.common.ApplicationException;
import com.hypeman.common.Ids;
import com.hypeman.goal.model.Goal;
import com.hypeman.goal.model.GoalChange;
import com.hypeman.goal.repository.GoalRepository;
import com.hypeman.user.service.UserService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goals synced between a user's devices. Each device pushes the goals it changed and pulls what changed elsewhere. The
 * newest change to a goal wins, by when the device made it, and a deleted goal stays deleted.
 */
@Service
public class GoalService {

    public static final int MAX_TITLE_LENGTH = 80;
    public static final int MAX_MOTIVATOR_ID_LENGTH = 100;
    /** Longer than any push takes to commit. */
    static final Duration SYNC_OVERLAP = Duration.ofMinutes(1);

    private final GoalRepository goals;
    private final UserService users;
    private final Clock clock;

    GoalService(GoalRepository goals, UserService users, Clock clock) {
        this.goals = goals;
        this.users = users;
        this.clock = clock;
    }

    /**
     * Stores what the app changed. A change dated in the future counts as made now, so a phone with its clock set ahead
     * can't win every later edit. Changes to the same goal in one batch apply oldest first.
     */
    @Transactional
    public void push(UUID userId, List<GoalChange> changes) {
        users.get(userId);
        changes.forEach(GoalService::check);
        Instant now = clock.instant();
        Map<UUID, Goal> stored = goals.findByUserIdAndGoalIdIn(userId, changes.stream().map(GoalChange::goalId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Goal::getGoalId, Function.identity()));
        changes.stream()
                .map(change -> change.changedAt().isAfter(now) ? withChangedAt(change, now) : change)
                .sorted(Comparator.comparing(GoalChange::changedAt))
                .forEach(change -> {
                    Goal goal = stored.computeIfAbsent(change.goalId(), goalId -> new Goal(Ids.uuidV7(clock), userId, goalId));
                    if (goal.apply(change)) {
                        goals.save(goal);
                    }
                });
    }

    /**
     * What changed since the app's last pull, deletions included, or with no {@code since}, every goal the user has.
     * The cursor to pass next time is a little before now: a push still committing while this reads gets its updated_at
     * from when it started, and the overlap catches it on the next pull. Seeing a goal twice changes nothing.
     */
    @Transactional(readOnly = true)
    public GoalPage pull(UUID userId, Instant since) {
        users.get(userId);
        Instant cursor = clock.instant().minus(SYNC_OVERLAP);
        List<Goal> changed = since == null
                ? goals.findByUserIdAndDeletedAtIsNullOrderBySortIndex(userId)
                : goals.findByUserIdAndUpdatedAtAfterOrderByUpdatedAt(userId, since);
        return new GoalPage(changed, cursor);
    }

    /** @param cursor pass as {@code since} on the next pull */
    public record GoalPage(List<Goal> goals, Instant cursor) {
    }

    private static void check(GoalChange change) {
        if (change.goalId() == null || change.changedAt() == null) {
            throw invalid("Every goal needs its goalId and changedAt.");
        }
        if (change.deleted()) {
            return;
        }
        if (change.title() == null || change.title().isBlank() || change.title().length() > MAX_TITLE_LENGTH) {
            throw invalid("A goal's title is 1 to " + MAX_TITLE_LENGTH + " characters.");
        }
        if (change.sortIndex() == null) {
            throw invalid("Every goal needs its sortIndex.");
        }
        if (change.motivatorId() != null && change.motivatorId().length() > MAX_MOTIVATOR_ID_LENGTH) {
            throw invalid("That motivatorId is too long.");
        }
    }

    private static GoalChange withChangedAt(GoalChange change, Instant changedAt) {
        return new GoalChange(change.goalId(), change.title(), change.sortIndex(), change.reminderAt(), change.startedAt(),
                change.completedAt(), change.motivatorId(), changedAt, change.deleted());
    }

    private static ApplicationException invalid(String message) {
        return new ApplicationException("VALIDATION_FAILED", message);
    }
}
