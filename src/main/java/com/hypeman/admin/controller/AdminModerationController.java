package com.hypeman.admin.controller;

import com.hypeman.admin.model.AuditEntry;
import com.hypeman.admin.service.AuditLog;
import com.hypeman.user.model.User;
import com.hypeman.user.service.UserService;
import com.hypeman.yell.service.ReportService;
import com.hypeman.yell.service.ReportService.QueueItem;
import com.hypeman.yell.service.ReportService.Resolution;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Moderation (AD-06): reported friend yells wait in a queue; the admin takes each down or dismisses its reports, and can
 * ban the sender. Behind the admin token (see SecurityConfig), and every action goes in the audit log.
 */
@RestController
@RequestMapping("/v1/admin")
@Validated
class AdminModerationController {

    private final ReportService reports;
    private final UserService users;
    private final AuditLog audit;

    AdminModerationController(ReportService reports, UserService users, AuditLog audit) {
        this.reports = reports;
        this.users = users;
        this.audit = audit;
    }

    @GetMapping("/reports")
    QueueResponse queue(@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return new QueueResponse(reports.queue(limit));
    }

    @PostMapping("/yells/{yellId}/take-down")
    Resolution takeDown(@PathVariable UUID yellId, @Valid @RequestBody(required = false) Note body) {
        Resolution resolution = reports.takeDown(yellId);
        audit.record("yell.take_down", "yell:" + yellId, Note.text(body));
        return resolution;
    }

    @PostMapping("/yells/{yellId}/dismiss")
    Resolution dismiss(@PathVariable UUID yellId, @Valid @RequestBody(required = false) Note body) {
        Resolution resolution = reports.dismiss(yellId);
        audit.record("reports.dismiss", "yell:" + yellId, Note.text(body));
        return resolution;
    }

    @PostMapping("/users/{userId}/ban")
    BanView ban(@PathVariable UUID userId, @Valid @RequestBody BanRequest body) {
        User user = users.ban(userId);
        audit.record("user.ban", "user:" + userId, body.reason().strip());
        return BanView.of(user);
    }

    @PostMapping("/users/{userId}/unban")
    BanView unban(@PathVariable UUID userId, @Valid @RequestBody(required = false) Note body) {
        User user = users.unban(userId);
        audit.record("user.unban", "user:" + userId, Note.text(body));
        return BanView.of(user);
    }

    @GetMapping("/audit")
    AuditResponse audit(@RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return new AuditResponse(audit.recent(limit).stream().map(AuditView::of).toList());
    }

    record QueueResponse(List<QueueItem> items) {
    }

    /** @param note why, for the audit log */
    record Note(@Size(max = 500) String note) {

        static String text(Note body) {
            return body == null || body.note() == null || body.note().isBlank() ? null : body.note().strip();
        }
    }

    /** @param reason why, for the audit log */
    record BanRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** @param bannedAt null once unbanned */
    record BanView(UUID userId, String displayName, Instant bannedAt) {

        static BanView of(User user) {
            return new BanView(user.getId(), user.getDisplayName(), user.getBannedAt());
        }
    }

    record AuditResponse(List<AuditView> entries) {
    }

    record AuditView(UUID id, String actor, String action, String target, String detail, Instant at) {

        static AuditView of(AuditEntry entry) {
            return new AuditView(entry.getId(), entry.getActor(), entry.getAction(), entry.getTarget(), entry.getDetail(), entry.getCreatedAt());
        }
    }
}
