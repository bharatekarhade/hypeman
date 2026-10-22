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
import com.hypeman.pack.model.Pack;
import com.hypeman.pack.model.PackStatus;
import com.hypeman.pack.repository.ClipRepository;
import com.hypeman.pack.repository.PackRepository;
import java.time.Clock;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PackService {

    private static final String MP4_CONTENT_TYPE = "video/mp4";
    private static final String QUICKTIME_CONTENT_TYPE = "video/quicktime";
    private static final int MAX_TITLE_LENGTH = 60;
    private static final int MAX_CAPTION_LENGTH = 120;

    private final PackRepository packRepository;
    private final ClipRepository clipRepository;
    private final MediaStorage mediaStorage;
    private final Clock clock;

    public PackService(PackRepository packRepository, ClipRepository clipRepository, MediaStorage mediaStorage, Clock clock) {
        this.packRepository = packRepository;
        this.clipRepository = clipRepository;
        this.mediaStorage = mediaStorage;
        this.clock = clock;
    }

    @Transactional
    public Pack createDefaultPack(String title, String description, boolean isExplicit) {
        if (title == null || title.isBlank() || title.trim().length() > MAX_TITLE_LENGTH) {
            throw new ApplicationException("VALIDATION_FAILED", "The title must not be blank or over 60 characters.");
        }
        String trimmed = title.trim();
        return packRepository.save(Pack.defaultPack(uniqueSlug(trimmed), trimmed, description, isExplicit));
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
     * Until processing exists, the original upload is played as is. Each attempt has its own key, so a file at it can only
     * be this attempt's upload, never an earlier one's.
     */
    @Transactional
    public Clip completeClipUpload(UUID clipId) {
        Clip clip = clipRepository.findById(clipId)
                .orElseThrow(() -> new ApplicationException("CLIP_NOT_FOUND", "That clip doesn't exist."));
        if (clip.getSourceKey() == null || mediaStorage.sizeOf(clip.getSourceKey()) < 0) {
            throw new ApplicationException("UPLOAD_NOT_FOUND", "The clip's video hasn't been uploaded yet.");
        }
        clip.markReady(clip.getSourceKey(), null, null, null, null);
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
     * Signed links to a published pack's videos. Only free packs for now; paid ones need purchases first. A pack that
     * isn't published looks like one that doesn't exist.
     */
    @Transactional(readOnly = true)
    public List<ClipMedia> media(UUID packId) {
        Pack pack = packRepository.findById(packId)
                .filter(found -> found.getStatus() == PackStatus.PUBLISHED)
                .orElseThrow(() -> new ApplicationException("PACK_NOT_FOUND", "That pack doesn't exist."));
        if (!pack.isFree()) {
            throw new ApplicationException("PACK_NOT_PURCHASED", "Buy this pack to download it.");
        }
        return clipRepository.findByPackIdOrderByRole(packId).stream()
                .sorted(Comparator.comparing(Clip::getRole))
                .map(clip -> new ClipMedia(clip.getRole(), clip.getCaption(), mediaStorage.presignDownload(clip.getVideoKey())))
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
