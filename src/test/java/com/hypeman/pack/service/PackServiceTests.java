package com.hypeman.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hypeman.TestcontainersConfiguration;
import com.hypeman.common.ApplicationException;
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
 * Default packs from creation to publishing, on real Postgres. S3 is mocked: an upload "exists" when sizeOf says so.
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

    @MockitoBean
    MediaStorage media;

    @BeforeEach
    void fakeS3() {
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
        String key = "packs/" + pack.getId() + "/start/source-" + clip.getId() + ".mp4";
        assertThat(clip.getStatus()).isEqualTo(ClipStatus.UPLOADING);
        assertThat(clip.getCaption()).isEqualTo("Get up. Do it. Now.");
        assertThat(clip.getSourceKey()).isEqualTo(key);
        assertThat(upload.uploadUrl().getPath()).isEqualTo("/" + key);
        assertThat(upload.headers()).containsEntry("Content-Type", "video/mp4");
        verify(media).presignUpload(eq(key), eq("video/mp4"), eq(SIZE));
    }

    @Test
    void uploadingTheSameRoleAgainReplacesTheClip() {
        Pack pack = draft();
        ClipUpload first = service.requestClipUpload(pack.getId(), ADMIN, ClipRole.NAG, "Up.", "video/mp4", SIZE);
        service.completeClipUpload(first.clipId());

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
    void finishingAnUploadMakesTheClipReady() {
        Pack pack = draft();
        UUID clipId = service.requestClipUpload(pack.getId(), ADMIN, ClipRole.CONGRATS, "Yes!", "video/mp4", SIZE).clipId();

        service.completeClipUpload(clipId);

        Clip clip = clips.findById(clipId).orElseThrow();
        assertThat(clip.getStatus()).isEqualTo(ClipStatus.READY);
        assertThat(clip.getVideoKey()).as("unprocessed for now: the original plays as is").isEqualTo(clip.getSourceKey());
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

        service.completeClipUpload(congrats);
        assertThat(service.publish(pack.getId()).getStatus()).isEqualTo(PackStatus.PUBLISHED);
        assertThat(packs.findById(pack.getId()).orElseThrow().getStatus()).isEqualTo(PackStatus.PUBLISHED);
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
        service.completeClipUpload(clipId);
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
