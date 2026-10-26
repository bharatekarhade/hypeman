package com.hypeman.yell.service;

import com.hypeman.common.ApplicationException;
import com.hypeman.common.Ids;
import com.hypeman.media.service.MediaStorage;
import com.hypeman.pack.model.ClipRole;
import com.hypeman.user.model.User;
import com.hypeman.user.model.UserBannedEvent;
import com.hypeman.user.service.UserService;
import com.hypeman.yell.model.Report;
import com.hypeman.yell.model.ReportReason;
import com.hypeman.yell.model.ReportStatus;
import com.hypeman.yell.model.Yell;
import com.hypeman.yell.model.YellDelivery;
import com.hypeman.yell.model.YellStatus;
import com.hypeman.yell.repository.ReportRepository;
import com.hypeman.yell.repository.YellDeliveryRepository;
import com.hypeman.yell.repository.YellRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reports on received yells (FD-04) and the moderation queue they feed (AD-06). The yell leaves the reporter's inbox at
 * once; a moderator then takes it down for everyone or dismisses the reports.
 */
@Service
public class ReportService {

    private final ReportRepository reports;
    private final YellDeliveryRepository deliveries;
    private final YellRepository yells;
    private final UserService users;
    private final MediaStorage media;
    private final Clock clock;

    ReportService(ReportRepository reports, YellDeliveryRepository deliveries, YellRepository yells, UserService users,
                  MediaStorage media, Clock clock) {
        this.reports = reports;
        this.deliveries = deliveries;
        this.yells = yells;
        this.users = users;
        this.media = media;
        this.clock = clock;
    }

    /** Only a recipient can report a yell. Reporting it again changes nothing. */
    @Transactional
    public void report(UUID userId, UUID yellId, ReportReason reason) {
        YellDelivery delivery = deliveries.findByYellIdAndRecipientId(yellId, userId)
                .orElseThrow(() -> new ApplicationException("YELL_NOT_FOUND", "That yell isn't one you received."));
        if (!reports.existsByReporterIdAndYellId(userId, yellId)) {
            reports.save(new Report(Ids.uuidV7(clock), userId, yellId, reason));
        }
        delivery.hide(clock.instant());
    }

    /** Reported yells waiting for a moderator, the longest waiting first, each with its open reports and its sender. */
    @Transactional(readOnly = true)
    public List<QueueItem> queue(int limit) {
        List<UUID> yellIds = reports.findReportedYellIds(Limit.of(limit));
        Map<UUID, Yell> byId = yells.findAllById(yellIds).stream().collect(Collectors.toMap(Yell::getId, Function.identity()));
        Map<UUID, List<Report>> open = reports.findByYellIdInAndStatusOrderByCreatedAt(yellIds, ReportStatus.OPEN).stream()
                .collect(Collectors.groupingBy(Report::getYellId));
        Map<UUID, User> senders = users.all(byId.values().stream().map(Yell::getSenderId).distinct().toList());
        Map<UUID, Long> takedowns = senders.keySet().stream().collect(Collectors.toMap(Function.identity(), reports::countTakedownsOf));
        return yellIds.stream().map(byId::get).map(yell -> {
            User sender = senders.get(yell.getSenderId());
            URI video = yell.getVideoKey() == null ? null : media.presignDownload(yell.getVideoKey());
            return new QueueItem(yell.getId(), yell.getStatus(), yell.getRole(), yell.getCaption(), yell.getDurationMs(),
                    media.presignDownload(yell.getAudioKey()), video, yell.getSentAt(),
                    new Sender(sender.getId(), sender.getDisplayName(), sender.isBanned(), sender.isDeleted(), takedowns.get(sender.getId())),
                    open.get(yell.getId()).stream().map(ReportView::of).toList());
        }).toList();
    }

    /** Takes the yell down for everyone and closes its reports. Taking it down again changes nothing. */
    @Transactional
    public Resolution takeDown(UUID yellId) {
        Yell yell = yell(yellId);
        yell.remove();
        return new Resolution(yellId, yell.getStatus(), reports.closeOpen(yellId, ReportStatus.REMOVED, clock.instant()));
    }

    /** The yell stays up for everyone else; reporters still don't see it. */
    @Transactional
    public Resolution dismiss(UUID yellId) {
        Yell yell = yell(yellId);
        return new Resolution(yellId, yell.getStatus(), reports.closeOpen(yellId, ReportStatus.DISMISSED, clock.instant()));
    }

    /** A banned user's yells all come down, so their open reports are settled with them. */
    @EventListener
    void onUserBanned(UserBannedEvent event) {
        reports.closeOpenOf(event.userId(), clock.instant());
    }

    private Yell yell(UUID yellId) {
        return yells.findById(yellId).orElseThrow(() -> new ApplicationException("YELL_NOT_FOUND", "That yell doesn't exist."));
    }

    /**
     * @param status REMOVED already if ffprobe found it too long, so it only needs dismissing
     * @param videoUrl null for an audio-only yell
     */
    public record QueueItem(UUID yellId, YellStatus status, ClipRole role, String caption, Integer durationMs, URI audioUrl,
                            URI videoUrl, Instant sentAt, Sender sender, List<ReportView> reports) {
    }

    /** @param takedowns how many of their yells moderators took down before */
    public record Sender(UUID id, String displayName, boolean banned, boolean deleted, long takedowns) {
    }

    public record ReportView(UUID id, UUID reporterId, ReportReason reason, Instant reportedAt) {

        static ReportView of(Report report) {
            return new ReportView(report.getId(), report.getReporterId(), report.getReason(), report.getCreatedAt());
        }
    }

    /** @param reportsClosed the open reports this settled */
    public record Resolution(UUID yellId, YellStatus status, int reportsClosed) {
    }
}
