package com.hypeman.pack.controller;

import com.hypeman.auth.service.TokenService;
import com.hypeman.pack.model.CatalogPack;
import com.hypeman.pack.model.ClipMedia;
import com.hypeman.pack.model.ClipRole;
import com.hypeman.pack.service.PackService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The store's catalog. Public: the app shows packs before anyone signs in. */
@RestController
@RequestMapping("/v1/packs")
class PackController {

    private final PackService packs;

    PackController(PackService packs) {
        this.packs = packs;
    }

    @GetMapping
    List<PackView> catalog() {
        return packs.catalog().stream().map(PackView::of).toList();
    }

    /** POST, not GET: each call signs fresh links. A paid pack needs the buyer's sign-in; a free one needs none. */
    @PostMapping("/{packId}/media")
    MediaResponse media(@PathVariable UUID packId, @AuthenticationPrincipal Jwt token) {
        return new MediaResponse(packs.media(packId, token == null ? null : TokenService.userId(token)));
    }

    /** @param productId the App Store product a paid pack is sold as; null when it's free */
    record PackView(UUID id, String slug, String title, String description, boolean isExplicit, boolean isFree, String productId,
                    List<ClipView> clips) {

        static PackView of(CatalogPack entry) {
            List<ClipView> clips = entry.clips().stream().map(clip -> new ClipView(clip.getRole(), clip.getCaption())).toList();
            return new PackView(entry.pack().getId(), entry.pack().getSlug(), entry.pack().getTitle(), entry.pack().getDescription(),
                    entry.pack().isExplicit(), entry.pack().isFree(), entry.pack().getProductId(), clips);
        }
    }

    record ClipView(ClipRole role, String caption) {
    }

    record MediaResponse(List<ClipMedia> clips) {
    }
}
