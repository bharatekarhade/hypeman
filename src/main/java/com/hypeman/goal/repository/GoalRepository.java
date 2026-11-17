package com.hypeman.goal.repository;

import com.hypeman.goal.model.Goal;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** A user's goals go when the user does (ON DELETE CASCADE). */
public interface GoalRepository extends JpaRepository<Goal, UUID> {

    /** Locked, so two devices pushing the same goal at once can't both win. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Goal> findByUserIdAndGoalIdIn(UUID userId, Collection<UUID> goalIds);

    List<Goal> findByUserIdAndDeletedAtIsNullOrderBySortIndex(UUID userId);

    long countByUserIdAndDeletedAtIsNull(UUID userId);

    List<Goal> findByUserIdAndUpdatedAtAfterOrderByUpdatedAt(UUID userId, Instant since);
}
