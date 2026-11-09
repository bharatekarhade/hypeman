package com.hypeman.goal.controller;

import com.hypeman.auth.service.TokenService;
import com.hypeman.goal.model.Goal;
import com.hypeman.goal.model.GoalChange;
import com.hypeman.goal.service.GoalService;
import com.hypeman.goal.service.GoalService.GoalPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's goals, synced between their devices. Goal ids are the app's own. */
@RestController
@RequestMapping("/v1/goals")
class GoalController {

    static final int MAX_CHANGES = 500;

    private final GoalService goals;

    GoalController(GoalService goals) {
        this.goals = goals;
    }

    /**
     * With {@code since}, the goals changed after it, deleted ones included; without, every goal the user has. Pass the
     * response's cursor as {@code since} next time.
     */
    @GetMapping
    PullResponse pull(@AuthenticationPrincipal Jwt token, @RequestParam(required = false) Instant since) {
        GoalPage page = goals.pull(TokenService.userId(token), since);
        return new PullResponse(page.goals().stream().map(GoalView::of).toList(), page.cursor());
    }

    /** 204: stored, unless the server already has a newer change to that goal. */
    @PutMapping
    ResponseEntity<Void> push(@AuthenticationPrincipal Jwt token, @Valid @RequestBody PushRequest body) {
        goals.push(TokenService.userId(token), body.goals().stream().map(GoalView::toChange).toList());
        return ResponseEntity.noContent().build();
    }

    record PushRequest(@NotNull @Size(max = MAX_CHANGES) List<GoalView> goals) {
    }

    record PullResponse(List<GoalView> goals, Instant cursor) {
    }

    /** A deleted goal has only its id, changedAt and deleted. */
    record GoalView(UUID id, String title, Integer sortIndex, Instant reminderAt, Instant startedAt, Instant completedAt,
                    String motivatorId, Instant changedAt, boolean deleted) {

        static GoalView of(Goal goal) {
            return new GoalView(goal.getGoalId(), goal.getTitle(), goal.getSortIndex(), goal.getReminderAt(), goal.getStartedAt(),
                    goal.getCompletedAt(), goal.getMotivatorId(), goal.getChangedAt(), goal.isDeleted());
        }

        GoalChange toChange() {
            return new GoalChange(id, title, sortIndex, reminderAt, startedAt, completedAt, motivatorId, changedAt, deleted);
        }
    }
}
