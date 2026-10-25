package com.hypeman.yell.controller;

import com.hypeman.auth.service.TokenService;
import com.hypeman.pack.model.ClipRole;
import com.hypeman.yell.model.ReportReason;
import com.hypeman.yell.service.ReportService;
import com.hypeman.yell.service.YellService;
import com.hypeman.yell.service.YellService.InboxPage;
import com.hypeman.yell.service.YellService.SendResult;
import com.hypeman.yell.service.YellService.YellUpload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Recording, sending, receiving and reporting friend yells. All need a signed-in user. */
@RestController
@Validated
class YellController {

    private final YellService yells;
    private final ReportService reports;

    YellController(YellService yells, ReportService reports) {
        this.yells = yells;
        this.reports = reports;
    }

    /** Step one of sending: links to PUT the recording to, valid for 15 minutes. */
    @PostMapping("/v1/yells/upload-urls")
    YellUpload upload(@AuthenticationPrincipal Jwt token, @Valid @RequestBody UploadRequest body) {
        return yells.createUpload(TokenService.userId(token), body.role(), body.caption(), body.audioSize(), body.videoSize(),
                body.videoContentType());
    }

    /** Step two, once the uploads finished. Safe to repeat. 402 SEND_CAP_REACHED past the free monthly cap. */
    @PostMapping("/v1/yells/{yellId}/send")
    SendResult send(@AuthenticationPrincipal Jwt token, @PathVariable UUID yellId, @Valid @RequestBody SendRequest body) {
        return yells.send(TokenService.userId(token), yellId, body.recipientIds());
    }

    @GetMapping("/v1/inbox")
    InboxPage inbox(@AuthenticationPrincipal Jwt token, @RequestParam(required = false) String cursor,
                    @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return yells.inbox(TokenService.userId(token), cursor, limit);
    }

    /** Into the user's library. */
    @PostMapping("/v1/inbox/{deliveryId}/save")
    ResponseEntity<Void> save(@AuthenticationPrincipal Jwt token, @PathVariable UUID deliveryId) {
        yells.save(TokenService.userId(token), deliveryId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/inbox/{deliveryId}/hide")
    ResponseEntity<Void> hide(@AuthenticationPrincipal Jwt token, @PathVariable UUID deliveryId) {
        yells.hide(TokenService.userId(token), deliveryId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/reports")
    ResponseEntity<Void> report(@AuthenticationPrincipal Jwt token, @Valid @RequestBody ReportRequest body) {
        reports.report(TokenService.userId(token), body.yellId(), body.reason());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /** @param caption the line spoken, shown as a caption */
    record UploadRequest(@NotNull ClipRole role, @NotBlank @Size(max = 120) String caption, @Positive long audioSize,
                         @Positive Long videoSize, String videoContentType) {
    }

    record SendRequest(@NotEmpty @Size(max = 20) List<@NotNull UUID> recipientIds) {
    }

    record ReportRequest(@NotNull UUID yellId, @NotNull ReportReason reason) {
    }
}
