package com.hypeman.pack.service;

import com.hypeman.common.ApplicationException;
import com.hypeman.common.Ids;
import com.hypeman.media.service.MediaStorage;
import com.hypeman.media.service.MediaStorage.PresignedUpload;
import com.hypeman.pack.model.CatalogPack;
import com.hypeman.pack.model.Clip;
import com.hypeman.pack.model.ClipMedia;
import com.hypeman.pack.model.ClipRole;
import com.hypeman.pack.model.ClipStatus;
import com.hypeman.pack.model.ClipUpload;
import com.hypeman.pack.model.ClipUploadedEvent;
import com.hypeman.pack.model.Pack;
import com.hypeman.pack.model.PackStatus;
import com.hypeman.pack.repository.ClipRepository;
import com.hypeman.pack.repository.PackRepository;
import com.hypeman.purchase.service.PurchaseService;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PackService {

    private static final String MP4_CONTENT_TYPE = "video/mp4";
    private static final String QUICKTIME_CONTENT_TYPE = "video/quicktime";
    private static final int MAX_TITLE_LENGTH = 60;
    private static final int MAX_CAPTION_LENGTH = 120;
    private static final Pattern PRODUCT_ID = Pattern.compile("[A-Za-z0-9-]+(\\.[A-Za-z0-9_-]+)+");
    /** Processing a 10-second clip takes seconds; one PROCESSING this long was lost, e.g. to a restart. */
    private static final Duration STUCK_PROCESSING = Duration.ofMinutes(10);

    private final PackRepository packRepository;
    private final ClipRepository clipRepository;
    private final MediaStorage mediaStorage;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final PurchaseService purchases;

    public PackService(PackRepository packRepository, ClipRepository clipRepository, MediaStorage mediaStorage, Clock clock,
                       ApplicationEventPublisher events, PurchaseService purchases) {
        this.packRepository = packRepository;
        this.clipRepository = clipRepository;
        this.mediaStorage = mediaStorage;
        this.clock = clock;
        this.events = events;
        this.purchases = purchases;
    }

    @Transactional
    public Pack createDefaultPack(String title, String description, boolean isExplicit) {
        return createDefaultPack(title, description, isExplicit, null);
    }

    /** @param productId the App Store product that sells it; null for a free pack */
    @Transactional
    public Pack createDefaultPack(String title, String description, boolean isExplicit, String productId) {
        if (title == null || title.isBlank() || title.trim().length() > MAX_TITLE_LENGTH) {
            throw new ApplicationException("VALIDATION_FAILED", "The title must not be blank or over 60 characters.");
        }
        if (productId != null && !PRODUCT_ID.matcher(productId).matches()) {
            throw new ApplicationException("VALIDATION_FAILED", "The product id must be a reverse-DNS id like com.hypeman.pack.coach.");
        }
        if (productId != null && packRepository.findByProductId(productId).isPresent()) {
            throw new ApplicationException("VALIDATION_FAILED", "Another pack is already sold as " + productId + ".");
        }
        String trimmed = title.trim();
        Pack pack = Pack.defaultPack(uniqueSlug(trimmed), trimmed, description, isExplicit);
        return packRepository.save(productId == null ? pack : pack.sellAs(productId));
    }

    /**
     * @param creatorId who is uploading; null means the admin, who may only upload to default packs (they have no
     *                  creator)
     */
    @Transactional
    public ClipUpload requestClipUpload(UUID packId, UUID creatorId, ClipRole role, String caption, String contentType, long sizeBytes) {
        Pack pack = findPack(packId);
        if (!Objects.equals(creatorId, pack.getCreatorId())) {
            throw new ApplicationException("PACK_NOT_OWNED", "You don't have permission to upload clips for this pack.");
        }
        if (pack.getStatus() != PackStatus.DRAFT) {
            throw new ApplicationException("PACK_NOT_EDITABLE", "Only a draft pack can take new clips.");
        }
        if (caption == null || caption.isBlank() || caption.trim().length() > MAX_CAPTION_LENGTH) {
            throw new ApplicationException("VALIDATION_FAILED", "The caption must not be blank or over 120 characters.");
        }
        if (!MP4_CONTENT_TYPE.equalsIgnoreCase(contentType) && !QUICKTIME_CONTENT_TYPE.equalsIgnoreCase(contentType)) {
            throw new ApplicationException("UNSUPPORTED_FILE_TYPE", "Upload an MP4 or MOV video.");
        }
        String trimmedCaption = caption.trim();
        Clip clip = clipRepository.findByPackIdAndRole(packId, role)
                .orElseGet(() -> clipRepository.save(new Clip(packId, role, trimmedCaption)));
        clip.updateCaption(trimmedCaption);
        String extension = QUICKTIME_CONTENT_TYPE.equalsIgnoreCase(contentType) ? "mov" : "mp4";
        clip.uploadStarted(sourceKey(packId, role, Ids.uuidV7(clock), extension));
        PresignedUpload upload = mediaStorage.presignUpload(clip.getSourceKey(), contentType, sizeBytes);
        return new ClipUpload(clip.getId(), upload.url(), upload.headers(), upload.expiresAt());
    }

    /**
     * Confirms the upload reached S3 and queues it for ClipProcessor; the clip turns READY or FAILED when that's done.
     * Each attempt has its own key, so a file at it can only be this attempt's upload, never an earlier one's. Calling
     * again changes nothing while processing runs or once it's READY, and retries a FAILED clip or a stuck one.
     */
    @Transactional
    public Clip completeClipUpload(UUID clipId) {
        Clip clip = clipRepository.findById(clipId)
                .orElseThrow(() -> new ApplicationException("CLIP_NOT_FOUND", "That clip doesn't exist."));
        boolean processing = clip.getStatus() == ClipStatus.PROCESSING
                && (clip.getUpdatedAt() == null || clip.getUpdatedAt().isAfter(clock.instant().minus(STUCK_PROCESSING)));
        if (processing || clip.getStatus() == ClipStatus.READY) {
            return clip;
        }
        if (clip.getSourceKey() == null || mediaStorage.sizeOf(clip.getSourceKey()) < 0) {
            throw new ApplicationException("UPLOAD_NOT_FOUND", "The clip's video hasn't been uploaded yet.");
        }
        clip.markProcessing();
        events.publishEvent(new ClipUploadedEvent(clip.getId(), clip.getSourceKey()));
        return clip;
    }

    /** A draft goes live once all four clips are ready. IN_REVIEW is allowed too, for creator packs once review exists. */
    @Transactional
    public Pack publish(UUID packId) {
        Pack pack = findPack(packId);
        if (pack.getStatus() != PackStatus.DRAFT && pack.getStatus() != PackStatus.IN_REVIEW) {
            throw new ApplicationException("PACK_NOT_EDITABLE", "Only a draft pack can be published.");
        }
        List<Clip> clips = clipRepository.findByPackIdOrderByRole(packId);
        boolean everyRoleReady = Arrays.stream(ClipRole.values())
                .allMatch(role -> clips.stream().anyMatch(clip -> clip.getRole() == role && clip.getStatus() == ClipStatus.READY));
        if (!everyRoleReady) {
            throw new ApplicationException("PACK_CLIPS_NOT_READY", "All four clips must be ready before publishing the pack.");
        }
        return pack.publish();
    }

    @Transactional(readOnly = true)
    public List<Pack> published() {
        return packRepository.findByStatusOrderByFeaturedRankAsc(PackStatus.PUBLISHED);
    }

    /** Every published pack with its clips. */
    @Transactional(readOnly = true)
    public List<CatalogPack> catalog() {
        return withClips(published());
    }

    /** Every pack whatever its status, newest first, for the admin. */
    @Transactional(readOnly = true)
    public List<CatalogPack> allPacks() {
        return withClips(packRepository.findAllByOrderByCreatedAtDesc());
    }

    /** One pack whatever its status, for the admin. */
    @Transactional(readOnly = true)
    public CatalogPack pack(UUID packId) {
        return withClips(List.of(findPack(packId))).getFirst();
    }

    /**
     * Signed links to a published pack's videos: anyone's for a free pack; for a paid one, only for its buyer. A pack that
     * isn't published looks like one that doesn't exist.
     *
     * @param userId null when nobody is signed in
     * @param signedTransaction the pack's StoreKit purchase, for a buyer who isn't signed in; may be null
     */
    @Transactional(readOnly = true)
    public List<ClipMedia> media(UUID packId, UUID userId, String signedTransaction) {
        Pack pack = packRepository.findById(packId)
                .filter(found -> found.getStatus() == PackStatus.PUBLISHED)
                .orElseThrow(() -> new ApplicationException("PACK_NOT_FOUND", "That pack doesn't exist."));
        if (!pack.isFree() && !purchases.owns(userId, pack, signedTransaction)) {
            throw new ApplicationException("PACK_NOT_PURCHASED", "Buy this pack to download it.");
        }
        return clipRepository.findByPackIdOrderByRole(packId).stream()
                .sorted(Comparator.comparing(Clip::getRole))
                .map(clip -> new ClipMedia(clip.getRole(), clip.getCaption(), mediaStorage.presignDownload(clip.getVideoKey()),
                        clip.getAlarmKey() != null ? mediaStorage.presignDownload(clip.getAlarmKey()) : null))
                .toList();
    }

    /** The packs with their clips in role order, fetched in two queries however many packs there are. */
    private List<CatalogPack> withClips(List<Pack> packs) {
        Map<UUID, List<Clip>> clipsByPack = clipRepository.findByPackIdIn(packs.stream().map(Pack::getId).toList()).stream()
                .sorted(Comparator.comparing(Clip::getRole))
                .collect(Collectors.groupingBy(Clip::getPackId));
        return packs.stream()
                .map(pack -> new CatalogPack(pack, clipsByPack.getOrDefault(pack.getId(), List.of())))
                .toList();
    }

    private Pack findPack(UUID packId) {
        return packRepository.findById(packId)
                .orElseThrow(() -> new ApplicationException("PACK_NOT_FOUND", "That pack doesn't exist."));
    }

    /** "Leg Day Coach" becomes leg-day-coach, or leg-day-coach-2, -3 and so on if that's taken. */
    private String uniqueSlug(String title) {
        String base = title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isEmpty()) {
            base = "pack";
        }
        Set<String> taken = packRepository.findSlugsLike(base);
        if (!taken.contains(base)) {
            return base;
        }
        int n = 2;
        while (taken.contains(base + "-" + n)) {
            n++;
        }
        return base + "-" + n;
    }

    /** A fresh key per upload attempt: reusing one would let a failed replacement complete with the old file. */
    private static String sourceKey(UUID packId, ClipRole role, UUID attemptId, String extension) {
        return "packs/" + packId + "/" + role.name().toLowerCase(Locale.ROOT) + "/source-" + attemptId + "." + extension;
    }
}
