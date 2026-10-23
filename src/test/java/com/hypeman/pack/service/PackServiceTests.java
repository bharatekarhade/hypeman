package com.hypeman.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hypeman.TestcontainersConfiguration;
import com.hypeman.common.ApplicationException;
import com.hypeman.media.service.ClipTranscoder;
import com.hypeman.media.service.ClipTranscoder.Outputs;
import com.hypeman.media.service.ClipTranscoder.Probe;
import com.hypeman.media.service.ClipTranscoder.TranscodeException;
import com.hypeman.media.service.MediaStorage;
import com.hypeman.media.service.MediaStorage.PresignedUpload;
import com.hypeman.pack.model.Clip;
import com.hypeman.pack.model.ClipRole;
import com.hypeman.pack.model.ClipStatus;
import com.hypeman.pack.model.ClipUpload;
import com.hypeman.pack.model.Pack;
import com.hypeman.pack.model.PackStatus;
import com.hypeman.pack.repository.ClipRepository;
import com.hypeman.pack.repository.PackRepository;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Default packs from creation to publishing, on real Postgres. S3 is mocked: an upload "exists" when sizeOf says so. So is
 * ffmpeg (ClipTranscoderTests covers the real thing), but processing still runs on its own thread, so tests wait for it.
 * Every title carries a random word, so tests sharing the database never collide on slugs.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("local")
class PackServiceTests {

    private static final long SIZE = 4_200_000;

    /** The admin acts on default packs, which have no creator. */
    private static final UUID ADMIN = null;

    @Autowired
    PackService service;

    @Autowired
    PackRepository packs;

    @Autowired
    ClipRepository clips;

    @Autowired
    ClipProcessor processor;

    @MockitoBean
    MediaStorage media;

    @MockitoBean
    ClipTranscoder transcoder;

    @BeforeEach
    void fakeS3AndFfmpeg() {
        when(transcoder.probe(any())).thenReturn(new Probe(4_200, true, true));
        when(transcoder.transcode(any(), any(), anyLong()))
                .thenReturn(new Outputs(Path.of("video.mp4"), Path.of("audio.m4a"), Path.of("alarm.caf"), Path.of("thumb.jpg")));
        when(media.presignUpload(anyString(), anyString(), anyLong())).thenAnswer(call -> new PresignedUpload(
                URI.create("https://hypeman-media-test.s3.ap-northeast-1.amazonaws.com/" + call.getArgument(0)),
                Map.of("Content-Type", call.getArgument(1)),
                Instant.parse("2026-10-21T09:15:00Z")));
        when(media.sizeOf(anyString())).thenReturn(SIZE);
    }

    @Test
    void aNewPackIsAFreeDraftWithASlugFromItsTitle() {
        String word = word();

        Pack pack = service.createDefaultPack("  Leg Day Coach " + word + "!  ", "Yells about squats", false);

        assertThat(pack.getSlug()).isEqualTo("leg-day-coach-" + word);
        assertThat(pack.getTitle()).isEqualTo("Leg Day Coach " + word + "!");
        assertThat(pack.getStatus()).isEqualTo(PackStatus.DRAFT);
        assertThat(pack.isFree()).isTrue();
        assertThat(pack.getCreatorId()).isNull();
    }

    @Test
    void theSameTitleGetsNumberedSlugs() {
        String title = "Coach " + word();
        String base = title.toLowerCase().replace(' ', '-');

        assertThat(service.createDefaultPack(title, "One", false).getSlug()).isEqualTo(base);
        assertThat(service.createDefaultPack(title, "Two", false).getSlug()).isEqualTo(base + "-2");
        assertThat(service.createDefaultPack(title, "Three", false).getSlug()).isEqualTo(base + "-3");
    }

    @Test
    void aTitleWithNoLettersStillGetsASlug() {
        assertThat(service.createDefaultPack("!!!", "Just noise", false).getSlug()).startsWith("pack");
    }

    @Test
    void aTitleMustBeOneToSixtyCharacters() {
        for (String title : new String[] {null, "", "   ", "x".repeat(61)}) {
            assertFailsWith("VALIDATION_FAILED", () -> service.createDefaultPack(title, "Bad title", false));
        }
        assertThat(service.createDefaultPack("x".repeat(59) + word().charAt(0), "Long but fine", false)).isNotNull();
    }

    @Test
    void askingToUploadSavesTheClipAndReturnsASignedLink() {
        Pack pack = draft();

        ClipUpload upload = service.requestClipUpload(pack.getId(), ADMIN, ClipRole.START, "Get up. Do it. Now.", "video/mp4", SIZE);

        Clip clip = clips.findById(upload.clipId()).orElseThrow();
        String key = clip.getSourceKey();
        assertThat(clip.getStatus()).isEqualTo(ClipStatus.UPLOADING);
        assertThat(clip.getCaption()).isEqualTo("Get up. Do it. Now.");
        assertThat(key).matches("packs/" + pack.getId() + "/start/source-[0-9a-f-]{36}\\.mp4");
        assertThat(upload.uploadUrl().getPath()).isEqualTo("/" + key);
        assertThat(upload.headers()).containsEntry("Content-Type", "video/mp4");
        verify(media).presignUpload(eq(key), eq("video/mp4"), eq(SIZE));
    }

    @Test
    void uploadingTheSameRoleAgainReplacesTheClip() {
        Pack pack = draft();
        ClipUpload first = service.requestClipUpload(pack.getId(), ADMIN, ClipRole.NAG, "Up.", "video/mp4", SIZE);
        completeAndWait(first.clipId());

        ClipUpload second = service.requestClipUpload(pack.getId(), ADMIN, ClipRole.NAG, "Up. Now.", "video/quicktime", SIZE);

        assertThat(second.clipId()).isEqualTo(first.clipId());
        Clip clip = clips.findById(second.clipId()).orElseThrow();
        assertThat(clip.getStatus()).as("back to uploading").isEqualTo(ClipStatus.UPLOADING);
        assertThat(clip.getCaption()).isEqualTo("Up. Now.");
        assertThat(clip.getSourceKey()).endsWith(".mov");
        assertThat(clip.getVideoKey()).as("the old processed files are forgotten").isNull();
        assertThat(clips.findByPackIdOrderByRole(pack.getId())).hasSize(1);
    }

    @Test
    void aReplacementThatNeverReachesS3CannotCompleteWithTheOldVideo() {
        Pack pack = draft();
        ClipUpload first = service.requestClipUpload(pack.getId(), ADMIN, ClipRole.START, "Old line.", "video/mp4", SIZE);
        completeAndWait(first.clipId());
        String oldKey = clips.findById(first.clipId()).orElseThrow().getSourceKey();
        // Only the first upload ever reaches S3.
        when(media.sizeOf(anyString())).thenReturn(-1L);
        when(media.sizeOf(oldKey)).thenReturn(SIZE);

        service.requestClipUpload(pack.getId(), ADMIN, ClipRole.START, "New line.", "video/mp4", SIZE);

        assertThat(clips.findById(first.clipId()).orElseThrow().getSourceKey()).as("same format, new key").isNotEqualTo(oldKey);
        assertFailsWith("UPLOAD_NOT_FOUND", () -> service.completeClipUpload(first.clipId()));
        assertThat(clips.findById(first.clipId()).orElseThrow().getStatus()).isEqualTo(ClipStatus.UPLOADING);
    }

    @Test
    void onlyVideosCanBeUploaded() {
        Pack pack = draft();

        for (String type : new String[] {null, "image/png", "audio/mpeg", "video/webm"}) {
            assertFailsWith("UNSUPPORTED_FILE_TYPE",
                    () -> service.requestClipUpload(pack.getId(), ADMIN, ClipRole.START, "Go.", type, SIZE));
        }
        assertThat(clips.findByPackIdOrderByRole(pack.getId())).isEmpty();
    }

    @Test
    void uploadingToAPackThatDoesNotExistFails() {
        assertFailsWith("PACK_NOT_FOUND",
                () -> service.requestClipUpload(UUID.randomUUID(), ADMIN, ClipRole.START, "Go.", "video/mp4", SIZE));
    }

    @Test
    void aCreatorCannotUploadToADefaultPack() {
        Pack pack = draft();

        assertFailsWith("PACK_NOT_OWNED",
                () -> service.requestClipUpload(pack.getId(), UUID.randomUUID(), ClipRole.START, "Mine now.", "video/mp4", SIZE));
        assertThat(clips.findByPackIdOrderByRole(pack.getId())).isEmpty();
    }

    @Test
    void aPublishedPackCannotTakeNewClips() {
        Pack pack = publishedPack();

        assertFailsWith("PACK_NOT_EDITABLE",
                () -> service.requestClipUpload(pack.getId(), ADMIN, ClipRole.START, "Sneaky.", "video/mp4", SIZE));
    }

    @Test
    void aCaptionMustBeOneToOneHundredTwentyCharacters() {
        Pack pack = draft();

        assertFailsWith("VALIDATION_FAILED",
                () -> service.requestClipUpload(pack.getId(), ADMIN, ClipRole.START, "   ", "video/mp4", SIZE));
        assertFailsWith("VALIDATION_FAILED",
                () -> service.requestClipUpload(pack.getId(), ADMIN, ClipRole.START, null, "video/mp4", SIZE));
        assertFailsWith("VALIDATION_FAILED",
                () -> service.requestClipUpload(pack.getId(), ADMIN, ClipRole.START, "x".repeat(121), "video/mp4", SIZE));
        assertThat(clips.findByPackIdOrderByRole(pack.getId())).isEmpty();

        UUID clipId = service.requestClipUpload(pack.getId(), ADMIN, ClipRole.START, "  Get up.  ", "video/mp4", SIZE).clipId();
        assertThat(clips.findById(clipId).orElseThrow().getCaption()).isEqualTo("Get up.");
    }

    @Test
    void finishingAnUploadProcessesTheClipIntoFilesBesideTheOriginal() {
        Pack pack = draft();
        UUID clipId = service.requestClipUpload(pack.getId(), ADMIN, ClipRole.CONGRATS, "Yes!", "video/quicktime", SIZE).clipId();
        String sourceKey = clips.findById(clipId).orElseThrow().getSourceKey();
        String attempt = sourceKey.substring(sourceKey.lastIndexOf("source-") + "source-".length(), sourceKey.lastIndexOf('.'));
        String folder = "packs/" + pack.getId() + "/congrats/";

        assertThat(service.completeClipUpload(clipId).getStatus()).isEqualTo(ClipStatus.PROCESSING);
        Clip clip = waitUntilProcessed(clipId);

        assertThat(clip.getStatus()).isEqualTo(ClipStatus.READY);
        assertThat(clip.getVideoKey()).isEqualTo(folder + "video-" + attempt + ".mp4");
        assertThat(clip.getAudioKey()).isEqualTo(folder + "audio-" + attempt + ".m4a");
        assertThat(clip.getAlarmKey()).isEqualTo(folder + "alarm-" + attempt + ".caf");
        assertThat(clip.getThumbKey()).isEqualTo(folder + "thumb-" + attempt + ".jpg");
        assertThat(clip.getDurationMs()).isEqualTo(4_200);
        verify(media).download(eq(sourceKey), any());
        verify(media).upload(eq(clip.getVideoKey()), eq(Path.of("video.mp4")), eq("video/mp4"));
        verify(media).upload(eq(clip.getAlarmKey()), eq(Path.of("alarm.caf")), eq("audio/x-caf"));
    }

    @Test
    void aClipOverTenSecondsFailsWithTheReason() {
        when(transcoder.probe(any())).thenReturn(new Probe(14_200, true, true));

        Clip clip = processedClip();

        assertThat(clip.getStatus()).isEqualTo(ClipStatus.FAILED);
        assertThat(clip.getFailureReason()).isEqualTo("Clips can be up to 10 seconds; this one is 14.");
        assertThat(clip.getVideoKey()).isNull();
    }

    @Test
    void aSilentClipFails() {
        when(transcoder.probe(any())).thenReturn(new Probe(4_200, true, false));

        assertThat(processedClip().getFailureReason()).isEqualTo("That video has no sound. A clip needs someone yelling in it.");
    }

    @Test
    void aVideoFfmpegCannotConvertFailsWithItsReason() {
        when(transcoder.transcode(any(), any(), anyLong())).thenThrow(new TranscodeException("We couldn't convert that video.", null));

        assertThat(processedClip().getFailureReason()).isEqualTo("We couldn't convert that video.");
    }

    @Test
    void aFailureOnOurSideCanBeRetriedByCompletingAgain() {
        doThrow(new ApplicationException("MEDIA_UNAVAILABLE", "S3 is down")).when(media).download(anyString(), any());
        Clip failed = processedClip();
        assertThat(failed.getStatus()).isEqualTo(ClipStatus.FAILED);
        assertThat(failed.getFailureReason()).startsWith("Processing failed on our side");

        doNothing().when(media).download(anyString(), any());
        Clip retried = completeAndWait(failed.getId());

        assertThat(retried.getStatus()).isEqualTo(ClipStatus.READY);
        assertThat(retried.getFailureReason()).isNull();
    }

    @Test
    void completingAReadyClipAgainChangesNothing() {
        Clip ready = processedClip();

        Clip again = service.completeClipUpload(ready.getId());

        assertThat(again.getStatus()).isEqualTo(ClipStatus.READY);
        assertThat(again.getVideoKey()).isEqualTo(ready.getVideoKey());
    }

    @Test
    void aResultForAReplacedUploadIsDropped() {
        Pack pack = draft();
        UUID clipId = service.requestClipUpload(pack.getId(), ADMIN, ClipRole.START, "Old.", "video/mp4", SIZE).clipId();
        String oldKey = clips.findById(clipId).orElseThrow().getSourceKey();
        service.requestClipUpload(pack.getId(), ADMIN, ClipRole.START, "New.", "video/mp4", SIZE);

        processor.process(clipId, oldKey);

        Clip clip = clips.findById(clipId).orElseThrow();
        assertThat(clip.getStatus()).isEqualTo(ClipStatus.UPLOADING);
        assertThat(clip.getSourceKey()).isNotEqualTo(oldKey);
        assertThat(clip.getVideoKey()).isNull();
    }

    @Test
    void finishingAnUploadThatNeverReachedS3Fails() {
        Pack pack = draft();
        UUID clipId = service.requestClipUpload(pack.getId(), ADMIN, ClipRole.REMINDER, "Time.", "video/mp4", SIZE).clipId();
        when(media.sizeOf(anyString())).thenReturn(-1L);

        assertFailsWith("UPLOAD_NOT_FOUND", () -> service.completeClipUpload(clipId));
        assertThat(clips.findById(clipId).orElseThrow().getStatus()).isEqualTo(ClipStatus.UPLOADING);
    }

    @Test
    void finishingAnUnknownClipFails() {
        assertFailsWith("CLIP_NOT_FOUND", () -> service.completeClipUpload(UUID.randomUUID()));
    }

    @Test
    void aPackNeedsAllFourClipsReadyToPublish() {
        Pack pack = draft();
        for (ClipRole role : new ClipRole[] {ClipRole.START, ClipRole.REMINDER, ClipRole.NAG}) {
            readyClip(pack, role);
        }
        UUID congrats = service.requestClipUpload(pack.getId(), ADMIN, ClipRole.CONGRATS, "Yes!", "video/mp4", SIZE).clipId();

        assertFailsWith("PACK_CLIPS_NOT_READY", () -> service.publish(pack.getId()));

        completeAndWait(congrats);
        assertThat(service.publish(pack.getId()).getStatus()).isEqualTo(PackStatus.PUBLISHED);
        assertThat(packs.findById(pack.getId()).orElseThrow().getStatus()).isEqualTo(PackStatus.PUBLISHED);
    }

    @Test
    void aPackCanOnlyBePublishedOnce() {
        Pack pack = publishedPack();

        assertFailsWith("PACK_NOT_EDITABLE", () -> service.publish(pack.getId()));
    }

    @Test
    void theAdminSeesEveryPackNewestFirstWithItsClips() {
        Pack published = publishedPack();
        Pack draft = draft();

        assertThat(service.allPacks()).extracting(entry -> entry.pack().getId())
                .containsSubsequence(draft.getId(), published.getId());
        assertThat(service.pack(published.getId()).clips()).extracting(Clip::getRole).containsExactly(ClipRole.values());
        assertThat(service.pack(draft.getId()).clips()).isEmpty();
        assertFailsWith("PACK_NOT_FOUND", () -> service.pack(UUID.randomUUID()));
    }

    @Test
    void publishingAPackThatDoesNotExistFails() {
        assertFailsWith("PACK_NOT_FOUND", () -> service.publish(UUID.randomUUID()));
    }

    @Test
    void theStoreListsOnlyPublishedPacks() {
        Pack published = publishedPack();
        Pack draft = draft();

        assertThat(service.published())
                .extracting(Pack::getId)
                .contains(published.getId())
                .doesNotContain(draft.getId());
        assertThat(service.published()).allMatch(pack -> pack.getStatus() == PackStatus.PUBLISHED);
    }

    private Pack draft() {
        return service.createDefaultPack("Coach " + word(), "Yells at you", false);
    }

    private Pack publishedPack() {
        Pack pack = draft();
        for (ClipRole role : ClipRole.values()) {
            readyClip(pack, role);
        }
        return service.publish(pack.getId());
    }

    private void readyClip(Pack pack, ClipRole role) {
        UUID clipId = service.requestClipUpload(pack.getId(), ADMIN, role, "Line for " + role, "video/mp4", SIZE).clipId();
        assertThat(completeAndWait(clipId).getStatus()).isEqualTo(ClipStatus.READY);
    }

    /** A START clip in a new draft, uploaded and through processing, however that went. */
    private Clip processedClip() {
        UUID clipId = service.requestClipUpload(draft().getId(), ADMIN, ClipRole.START, "Go.", "video/mp4", SIZE).clipId();
        return completeAndWait(clipId);
    }

    private Clip completeAndWait(UUID clipId) {
        service.completeClipUpload(clipId);
        return waitUntilProcessed(clipId);
    }

    private Clip waitUntilProcessed(UUID clipId) {
        return await().atMost(Duration.ofSeconds(10))
                .until(() -> clips.findById(clipId).orElseThrow(), clip -> clip.getStatus() != ClipStatus.PROCESSING);
    }

    private static String word() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static void assertFailsWith(String code, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).code())
                .isEqualTo(code);
    }
}
