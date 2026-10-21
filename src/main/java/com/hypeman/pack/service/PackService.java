package com.hypeman.pack.service;

import com.hypeman.common.ApplicationException;
import com.hypeman.media.service.MediaStorage;
import com.hypeman.media.service.MediaStorage.*;
import com.hypeman.pack.model.*;
import com.hypeman.pack.repository.ClipRepository;
import com.hypeman.pack.repository.PackRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class PackService {

    private static final String QUICKTIME_CONTENT_TYPE = "video/quicktime";

    private final PackRepository packRepository;
    private final ClipRepository clipRepository;
    private final MediaStorage mediaStorage;

    public PackService(PackRepository packRepository, ClipRepository clipRepository, MediaStorage mediaStorage){
        this.packRepository = packRepository;
        this.clipRepository = clipRepository;
        this.mediaStorage = mediaStorage;
    }

    @Transactional
    public Pack createDefaultPack(String title, String description, Boolean isExplicit){
        if(title.isEmpty() || title.length()>60){
            throw new ApplicationException(
                    "VALIDATION_FAILED",
                    "The title must not be blank or over 60 characters."
            );
        }
        String slug = uniqQueueSlug(title);
        Pack pack = Pack.defaultPack(slug, title.trim(), description, isExplicit);
        return packRepository.save(pack);

    }

     @Transactional
     public ClipUpload requestClipUpload(UUID packId, UUID creatorId, ClipRole role, String caption, String contentType, long sizeBytes){
            Optional<Pack> pack = packRepository.findById(packId);
            if(pack.isEmpty()){
                throw new ApplicationException(
                        "PACK_NOT_FOUND",
                        "The pack is not found"
                );
            };
            if (creatorId == null || !creatorId.equals(pack.get().getCreatorId())) {
                throw new ApplicationException(
                        "PACK_NOT_OWNED",
                        "You don't have permission to upload clips for this pack."
                );
            }
         boolean isDraft = pack
                 .map(pack1 -> pack1.getStatus() == PackStatus.DRAFT)
                 .orElse(false);
            if(!isDraft){
                throw new ApplicationException(
                        "PACK_NOT_EDITABLE",
                        "Pack is not editable please set the pack in draft"
                );
            };
            if(contentType == null || !(contentType.equalsIgnoreCase("video/mp4") || contentType.equalsIgnoreCase(QUICKTIME_CONTENT_TYPE))){
                throw new ApplicationException(
                        "UNSUPPORTED_FILE_TYPE",
                        "The video formate is not supported"
                );
            };
            Clip clip = clipRepository.findByPackIdAndRole(packId, role)
                    .orElseGet(() -> new Clip(packId, role, caption));
            clip.updateCaption(caption);
            clip = clipRepository.saveAndFlush(clip);
            String extension = contentType.equalsIgnoreCase(QUICKTIME_CONTENT_TYPE) ? "mov" : "mp4";
            String key = keyBuilder(packId,role,clip.getId(), extension);
            clip.uploadStarted(key);
            clipRepository.save(clip);
            PresignedUpload presignedUpload = mediaStorage.presignUpload(key, contentType, sizeBytes);
            return new ClipUpload(clip.getId(), presignedUpload.url(),presignedUpload.headers(),presignedUpload.expiresAt() );

     }

     @Transactional
     public Clip completeClipUpload(UUID clipId){
         Clip clip = clipRepository.findById(clipId)
                 .orElseThrow(() -> new ApplicationException(
                         "CLIP_NOT_FOUND",
                         "Clip doesn't exist"
                 ));
         String sourceKey = clip.getSourceKey();
         long size = mediaStorage.sizeOf(sourceKey);
         if(size==-1){
             throw new ApplicationException(
                     "UPLOAD_NOT_FOUND",
                     "The upload for the clip doesn't exist"
             );
         };
         clip.markReady(null,sourceKey,null,null,null);
         return clipRepository.save(clip);
     }

     @Transactional
     public Pack publish(UUID packId){
        Pack pack = packRepository.findById(packId).orElseThrow(() -> new ApplicationException(
                "PACK_NOT_FOUND",
                "The pack is not found"
        ));
        List<Clip> clips = clipRepository.findByPackIdOrderByRole(packId);
        boolean everyRoleReady = java.util.Arrays.stream(ClipRole.values())
                .allMatch(role -> clips.stream().anyMatch(clip ->
                        clip.getRole() == role && clip.getStatus() == ClipStatus.READY));
        if (!everyRoleReady) {
            throw new ApplicationException(
                    "PACK_CLIPS_NOT_READY",
                    "All four clip roles must be ready before publishing the pack."
            );
        }
        return packRepository.save(pack.publish());
     }

     @Transactional
     public List<Pack> published(){
        return packRepository.findByStatusOrderByFeaturedRankAsc(PackStatus.PUBLISHED);
     }

    private String uniqQueueSlug(String title){
        String base = title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)","");
        if(base.isEmpty()){
            base = "pack";
        }
        Set<String> takenSlugs = packRepository.findSlugsLike(base);
        if(!takenSlugs.contains(base)){
            return base;
        }
        int n = 2;
        while(takenSlugs.contains(base+"-"+n)){
            n++;
        }
        return base+"-"+n;
    }

    private String keyBuilder(UUID packId, ClipRole role, UUID clipId, String extension){
        return "packs/" + packId + "/" + role.toString().toLowerCase(Locale.ROOT) + "/source-" + clipId + "." + extension;
    }
}
