package com.hypeman.yell.service;

import com.hypeman.common.ApplicationException;
import com.hypeman.common.Ids;
import com.hypeman.friend.service.FriendshipService;
import com.hypeman.media.service.MediaStorage;
import com.hypeman.media.service.MediaStorage.PresignedUpload;
import com.hypeman.pack.model.ClipRole;
import com.hypeman.user.model.UserBannedEvent;
import com.hypeman.user.model.UserDeletedEvent;
import com.hypeman.user.model.UserView;
import com.hypeman.user.service.UserService;
import com.hypeman.yell.config.YellProperties;
import com.hypeman.yell.model.InboxRow;
import com.hypeman.yell.model.Yell;
import com.hypeman.yell.model.YellDelivery;
import com.hypeman.yell.model.YellSentEvent;
import com.hypeman.yell.model.YellStatus;
import com.hypeman.yell.repository.YellDeliveryRepository;
import com.hypeman.yell.repository.YellRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Friend yells (FD-02, FD-03): the app uploads a recording straight to S3, then sends it to friends, who find it in their
 * inbox. Sending is safe to repeat: each friend gets a yell once, and only its first send counts toward the free cap.
 */
@Service
public class YellService {

    private static final String AUDIO_TYPE = "audio/mp4";
    private static final Map<String, String> VIDEO_TYPES = Map.of("video/mp4", "mp4", "video/quicktime", "mov");
    /** Stands in for "nobody" in a NOT IN list, which can't be empty. */
    private static final List<UUID> NOBODY = List.of(new UUID(0, 0));

    private final YellRepository yells;
    private final YellDeliveryRepository deliveries;
    private final FriendshipService friendships;
    private final UserService users;
    private final MediaStorage media;
    private final YellProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    YellService(YellRepository yells, YellDeliveryRepository deliveries, FriendshipService friendships, UserService users,
                MediaStorage media, YellProperties properties, ApplicationEventPublisher events, Clock clock) {
        this.yells = yells;
        this.deliveries = deliveries;
        this.friendships = friendships;
        this.users = users;
        this.media = media;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Creates the yell and links to upload its files, which must be exactly the sizes given (FD-06).
     *
     * @param videoContentType video/mp4 or video/quicktime; null with {@code videoSize} for an audio-only yell
     */
    @Transactional
    public YellUpload createUpload(UUID userId, ClipRole role, String caption, long audioSize, Long videoSize, String videoContentType) {
        users.get(userId);
        checkSize(audioSize, properties.maxAudioSize().toBytes(), "Audio");
        String videoExtension = null;
        if (videoSize != null) {
            videoExtension = VIDEO_TYPES.get(videoContentType);
            if (videoExtension == null) {
                throw new ApplicationException("UNSUPPORTED_FILE_TYPE", "Videos must be MP4 or MOV.");
            }
            checkSize(videoSize, properties.maxVideoSize().toBytes(), "Video");
        }
        UUID id = Ids.uuidV7(clock);
        String prefix = "yells/" + id + "/";
        Yell yell = yells.save(new Yell(id, userId, role, caption.strip(), prefix + "audio.m4a",
                videoExtension == null ? null : prefix + "video." + videoExtension));
        PresignedUpload audio = media.presignUpload(yell.getAudioKey(), AUDIO_TYPE, audioSize);
        PresignedUpload video = videoSize == null ? null : media.presignUpload(yell.getVideoKey(), videoContentType, videoSize);
        return new YellUpload(id, audio, video);
    }

    /**
     * Sends the yell to friends. Recipients who already have it are skipped, and so, silently, are any who blocked the
     * sender. The first send checks the uploads are there and counts toward the free monthly cap.
     */
    @Transactional
    public SendResult send(UUID userId, UUID yellId, List<UUID> recipientIds) {
        Yell yell = yells.findByIdAndSenderId(yellId, userId)
                .orElseThrow(() -> new ApplicationException("YELL_NOT_FOUND", "That yell doesn't exist."));
        if (yell.getStatus() == YellStatus.REMOVED) {
            throw new ApplicationException("YELL_REMOVED", "That yell was taken down, so it can't be sent.");
        }
        List<UUID> recipients = List.copyOf(new LinkedHashSet<>(recipientIds));
        for (UUID recipient : recipients) {
            if (!friendships.areFriends(userId, recipient)) {
                throw new ApplicationException("NOT_FRIENDS", "You can only yell at friends.");
            }
        }
        boolean firstSend = yell.getStatus() == YellStatus.UPLOADING;
        if (firstSend) {
            if (!uploaded(yell.getAudioKey()) || (yell.getVideoKey() != null && !uploaded(yell.getVideoKey()))) {
                throw new ApplicationException("UPLOAD_NOT_FOUND", "The recording hasn't finished uploading. Try again in a moment.");
            }
            Integer left = sendsLeft(userId);
            if (left != null && left <= 0) {
                throw new ApplicationException("SEND_CAP_REACHED",
                        "You've sent your " + properties.freeSendsPerMonth() + " free yells this month. Get Pro to send more.");
            }
            yell.markSent(clock.instant());
        }
        List<UUID> delivered = new ArrayList<>();
        for (UUID recipient : recipients) {
            if (!deliveries.existsByYellIdAndRecipientId(yellId, recipient) && !friendships.isBlocked(userId, recipient)) {
                deliveries.save(new YellDelivery(Ids.uuidV7(clock), yellId, recipient));
                delivered.add(recipient);
            }
        }
        if (firstSend || !delivered.isEmpty()) {
            events.publishEvent(new YellSentEvent(yellId, delivered, firstSend));
        }
        return new SendResult(recipients.size(), sendsLeft(userId));
    }

    /** Free sends left this calendar month (UTC); null for Pro, which has no cap. */
    @Transactional(readOnly = true)
    public Integer sendsLeft(UUID userId) {
        if (users.get(userId).isProAt(clock.instant())) {
            return null;
        }
        Instant monthStart = LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        long sent = yells.countBySenderIdAndSentAtGreaterThanEqual(userId, monthStart);
        return (int) Math.max(0, properties.freeSendsPerMonth() - sent);
    }

    /** Newest first, without hidden ones, taken-down ones, or ones from people the user blocked. */
    @Transactional(readOnly = true)
    public InboxPage inbox(UUID userId, String cursor, int limit) {
        List<UUID> blocked = friendships.blockedBy(userId);
        List<UUID> hiddenSenders = blocked.isEmpty() ? NOBODY : blocked;
        Limit page = Limit.of(limit + 1);
        List<InboxRow> rows;
        if (cursor == null) {
            rows = deliveries.inbox(userId, YellStatus.READY, hiddenSenders, page);
        } else {
            Cursor after = Cursor.decode(cursor);
            rows = deliveries.inboxAfter(userId, YellStatus.READY, hiddenSenders, after.createdAt(), after.id(), page);
        }
        boolean more = rows.size() > limit;
        List<InboxRow> shown = more ? rows.subList(0, limit) : rows;
        Map<UUID, UserView> senders = users.views(shown.stream().map(row -> row.yell().getSenderId()).distinct().toList());
        List<InboxItem> items = shown.stream().map(row -> item(row, senders.get(row.yell().getSenderId()))).toList();
        String next = more ? new Cursor(shown.getLast().delivery().getCreatedAt(), shown.getLast().delivery().getId()).encode() : null;
        return new InboxPage(items, next);
    }

    @Transactional
    public void save(UUID userId, UUID deliveryId) {
        delivery(userId, deliveryId).save(clock.instant());
    }

    @Transactional
    public void hide(UUID userId, UUID deliveryId) {
        delivery(userId, deliveryId).hide(clock.instant());
    }

    /** A deleted user's yells disappear from every inbox at once. */
    @EventListener
    void onUserDeleted(UserDeletedEvent event) {
        yells.removeAllBy(event.userId());
    }

    /** So does a banned user's. */
    @EventListener
    void onUserBanned(UserBannedEvent event) {
        yells.removeAllBy(event.userId());
    }

    private YellDelivery delivery(UUID userId, UUID deliveryId) {
        return deliveries.findByIdAndRecipientId(deliveryId, userId)
                .orElseThrow(() -> new ApplicationException("DELIVERY_NOT_FOUND", "That yell isn't in your inbox."));
    }

    private InboxItem item(InboxRow row, UserView sender) {
        Yell yell = row.yell();
        URI video = yell.getVideoKey() == null ? null : media.presignDownload(yell.getVideoKey());
        return new InboxItem(row.delivery().getId(), yell.getId(), sender, yell.getRole(), yell.getCaption(), yell.getDurationMs(),
                media.presignDownload(yell.getAudioKey()), video, row.delivery().getSavedAt(), row.delivery().getCreatedAt());
    }

    private boolean uploaded(String key) {
        return media.sizeOf(key) > 0;
    }

    private static void checkSize(long size, long max, String what) {
        if (size <= 0 || size > max) {
            throw new ApplicationException("UPLOAD_TOO_LARGE", what + " files can be up to " + (max / 1_000_000) + " MB.");
        }
    }

    /** @param videoUpload null for an audio-only yell */
    public record YellUpload(UUID yellId, PresignedUpload audioUpload, PresignedUpload videoUpload) {
    }

    /** @param sendsLeftThisMonth null for Pro */
    public record SendResult(int recipients, Integer sendsLeftThisMonth) {
    }

    /**
     * @param sender null if their account was deleted since
     * @param durationMs null until the server has checked the files
     * @param videoUrl null for an audio-only yell
     */
    public record InboxItem(UUID deliveryId, UUID yellId, UserView sender, ClipRole role, String caption, Integer durationMs,
                            URI audioUrl, URI videoUrl, Instant savedAt, Instant receivedAt) {
    }

    /** @param nextCursor pass back for the next page; null on the last one */
    public record InboxPage(List<InboxItem> deliveries, String nextCursor) {
    }

    /** Where a page ended, opaque to the app. */
    private record Cursor(Instant createdAt, UUID id) {

        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString((createdAt + "~" + id).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("~", 2);
                return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException e) {
                throw new ApplicationException("VALIDATION_FAILED", "cursor isn't one the server gave out.");
            }
        }
    }
}
