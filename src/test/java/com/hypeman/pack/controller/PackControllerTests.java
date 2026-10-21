package com.hypeman.pack.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.hypeman.TestcontainersConfiguration;
import com.hypeman.media.service.MediaStorage;
import com.hypeman.media.service.MediaStorage.PresignedUpload;
import com.hypeman.pack.model.ClipRole;
import com.hypeman.pack.model.Pack;
import com.hypeman.pack.service.PackService;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The public catalog, with no sign-in. S3 is mocked: a download link is the key on a fake host. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PackControllerTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    PackService service;

    @MockitoBean
    MediaStorage media;

    @BeforeEach
    void fakeS3() {
        when(media.presignUpload(anyString(), anyString(), anyLong())).thenAnswer(call ->
                new PresignedUpload(URI.create("https://s3.test/" + call.getArgument(0)), Map.of(), Instant.now()));
        when(media.presignDownload(anyString())).thenAnswer(call -> URI.create("https://s3.test/" + call.getArgument(0) + "?signed"));
        when(media.sizeOf(anyString())).thenReturn(4_200_000L);
    }

    @Test
    void theCatalogListsPublishedPacksWithTheirClipsAndNoDrafts() throws Exception {
        Pack published = publishedPack();
        Pack draft = service.createDefaultPack("Draft " + word(), "Not yet", false);

        MvcTestResult result = mvc.get().uri("/v1/packs").exchange();

        assertThat(result).hasStatusOk();
        String pack = "$[?(@.id == '" + published.getId() + "')]";
        assertThat(this.<List<String>>read(result, pack + ".slug")).containsExactly(published.getSlug());
        assertThat(this.<List<Boolean>>read(result, pack + ".isFree")).containsExactly(true);
        assertThat(this.<List<Boolean>>read(result, pack + ".isExplicit")).containsExactly(false);
        assertThat(this.<List<String>>read(result, pack + ".clips[*].role")).containsExactly("START", "REMINDER", "NAG", "CONGRATS");
        assertThat(this.<List<String>>read(result, pack + ".clips[0].caption")).containsExactly("Line for START");
        assertThat(this.<List<String>>read(result, "$[*].id")).doesNotContain(draft.getId().toString());
    }

    @Test
    void theCatalogLeavesOutStoragePaths() throws Exception {
        publishedPack();

        String body = mvc.get().uri("/v1/packs").exchange().getResponse().getContentAsString();

        assertThat(body).doesNotContain("packs/").doesNotContain("Key");
    }

    @Test
    void mediaGivesASignedVideoLinkPerClip() throws Exception {
        Pack pack = publishedPack();

        MvcTestResult result = mvc.post().uri("/v1/packs/{id}/media", pack.getId()).exchange();

        assertThat(result).hasStatusOk();
        assertThat(this.<List<String>>read(result, "$.clips[*].role")).containsExactly("START", "REMINDER", "NAG", "CONGRATS");
        assertThat(this.<List<String>>read(result, "$.clips[*].videoUrl"))
                .hasSize(4)
                .allMatch(url -> url.startsWith("https://s3.test/packs/" + pack.getId() + "/") && url.endsWith("?signed"));
    }

    @Test
    void mediaForADraftPackLooksLikeNoPackAtAll() throws Exception {
        Pack draft = service.createDefaultPack("Draft " + word(), "Not yet", false);

        assertError(mvc.post().uri("/v1/packs/{id}/media", draft.getId()).exchange(), HttpStatus.NOT_FOUND, "PACK_NOT_FOUND");
    }

    @Test
    void mediaForAPackThatDoesNotExistIs404() throws Exception {
        assertError(mvc.post().uri("/v1/packs/{id}/media", UUID.randomUUID()).exchange(), HttpStatus.NOT_FOUND, "PACK_NOT_FOUND");
    }

    @Test
    void otherPackEndpointsStillNeedSignIn() throws Exception {
        assertError(mvc.post().uri("/v1/packs").exchange(), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        assertError(mvc.get().uri("/v1/packs/{id}/media", UUID.randomUUID()).exchange(), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    }

    private Pack publishedPack() {
        Pack pack = service.createDefaultPack("Coach " + word(), "Yells at you", false);
        for (ClipRole role : ClipRole.values()) {
            UUID clipId = service.requestClipUpload(pack.getId(), null, role, "Line for " + role, "video/mp4", 4_200_000).clipId();
            service.completeClipUpload(clipId);
        }
        return service.publish(pack.getId());
    }

    private static String word() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private <T> T read(MvcTestResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    private void assertError(MvcTestResult result, HttpStatus status, String code) throws Exception {
        assertThat(result).hasStatus(status);
        assertThat(this.<String>read(result, "$.code")).isEqualTo(code);
    }
}
