package com.hypeman.pack.controller;

import com.hypeman.admin.service.AuditLog;
import com.hypeman.pack.model.CatalogPack;
import com.hypeman.pack.model.Clip;
import com.hypeman.pack.model.ClipRole;
import com.hypeman.pack.model.ClipStatus;
import com.hypeman.pack.model.ClipUpload;
import com.hypeman.pack.model.Pack;
import com.hypeman.pack.model.PackStatus;
import com.hypeman.pack.service.PackService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Making default packs: create a draft, upload a video for each of the four roles straight to S3, confirm each upload,
 * then publish. Behind the admin token (see SecurityConfig), so there's no creator: every pack made here is a default pack.
 * Every change goes in the audit log.
 */
@RestController
@RequestMapping("/v1/admin")
class AdminPackController {

    private final PackService packs;
    private final AuditLog audit;

    AdminPackController(PackService packs, AuditLog audit) {
        this.packs = packs;
        this.audit = audit;
    }

    @GetMapping("/packs")
    List<AdminPackView> list() {
        return packs.allPacks().stream().map(AdminPackView::of).toList();
    }

    @GetMapping("/packs/{packId}")
    AdminPackView get(@PathVariable UUID packId) {
        return AdminPackView.of(packs.pack(packId));
    }

    @PostMapping("/packs")
    ResponseEntity<AdminPackView> create(@Valid @RequestBody CreatePackRequest body) {
        Pack pack = packs.createDefaultPack(body.title(), body.description(), body.isExplicit(), body.productId());
        audit.record("pack.create", "pack:" + pack.getId(), body.title());
        return ResponseEntity.created(URI.create("/v1/admin/packs/" + pack.getId())).body(AdminPackView.of(new CatalogPack(pack, List.of())));
    }

    /** Step one of a clip: a signed link to PUT the video to. Uploading a role again replaces that role's clip. */
    @PostMapping("/packs/{packId}/clips")
    ClipUpload requestUpload(@PathVariable UUID packId, @Valid @RequestBody ClipUploadRequest body) {
        ClipUpload upload = packs.requestClipUpload(packId, null, body.role(), body.caption(), body.contentType(), body.sizeBytes());
        audit.record("clip.upload", "pack:" + packId, body.role().name());
        return upload;
    }

    /** Step two, once the PUT to S3 has finished. */
    @PostMapping("/clips/{clipId}/complete")
    AdminClipView completeUpload(@PathVariable UUID clipId) {
        Clip clip = packs.completeClipUpload(clipId);
        audit.record("clip.complete", "clip:" + clipId, null);
        return AdminClipView.of(clip);
    }

    @PostMapping("/packs/{packId}/publish")
    AdminPackView publish(@PathVariable UUID packId) {
        packs.publish(packId);
        audit.record("pack.publish", "pack:" + packId, null);
        return AdminPackView.of(packs.pack(packId));
    }

    /** @param productId the App Store product that sells it, set up in App Store Connect; leave it out for a free pack */
    record CreatePackRequest(@NotNull String title, @NotNull @Size(max = 500) String description, boolean isExplicit,
                             @Size(max = 100) String productId) {
    }

    /** The service checks the caption and content type, so its error messages match the creator flow's. */
    record ClipUploadRequest(@NotNull ClipRole role, String caption, String contentType, @Positive long sizeBytes) {
    }

    record AdminPackView(UUID id, String slug, String title, String description, boolean isExplicit, boolean isFree,
                         String productId, PackStatus status, List<AdminClipView> clips) {

        static AdminPackView of(CatalogPack entry) {
            Pack pack = entry.pack();
            return new AdminPackView(pack.getId(), pack.getSlug(), pack.getTitle(), pack.getDescription(), pack.isExplicit(),
                    pack.isFree(), pack.getProductId(), pack.getStatus(), entry.clips().stream().map(AdminClipView::of).toList());
        }
    }

    record AdminClipView(UUID id, ClipRole role, String caption, ClipStatus status, String failureReason) {

        static AdminClipView of(Clip clip) {
            return new AdminClipView(clip.getId(), clip.getRole(), clip.getCaption(), clip.getStatus(), clip.getFailureReason());
        }
    }
}
