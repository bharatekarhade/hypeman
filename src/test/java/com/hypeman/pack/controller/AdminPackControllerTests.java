package com.hypeman.pack.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.hypeman.TestcontainersConfiguration;
import com.hypeman.auth.config.AdminProperties;
import com.hypeman.media.service.ClipTranscoder;
import com.hypeman.media.service.ClipTranscoder.Outputs;
import com.hypeman.media.service.ClipTranscoder.Probe;
import com.hypeman.media.service.MediaStorage;
import com.hypeman.media.service.MediaStorage.PresignedUpload;
import com.hypeman.pack.model.ClipRole;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Making a default pack over HTTP with the admin token, from draft to the public catalog. The token is whatever the local
 * profile resolves to, so a developer's own .env doesn't break the tests. S3 and ffmpeg are mocked as in PackControllerTests.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AdminPackControllerTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    AdminProperties admin;

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
                URI.create("https://s3.test/" + call.getArgument(0)), Map.of("Content-Type", call.getArgument(1)), Instant.now()));
        when(media.presignDownload(anyString())).thenAnswer(call -> URI.create("https://s3.test/" + call.getArgument(0) + "?signed"));
        when(media.sizeOf(anyString())).thenReturn(4_200_000L);
    }

    @Test
    void aPackGoesFromDraftToTheCatalog() throws Exception {
        String title = "Leg Day " + word();
        MvcTestResult created = post("/v1/admin/packs", """
                {"title": "%s", "description": "Yells about squats", "isExplicit": true}""".formatted(title));

        assertThat(created).hasStatus(HttpStatus.CREATED);
        String packId = read(created, "$.id");
        assertThat(created).headers().hasValue(HttpHeaders.LOCATION, "/v1/admin/packs/" + packId);
        assertThat(this.<String>read(created, "$.status")).isEqualTo("DRAFT");
        assertThat(this.<Boolean>read(created, "$.isExplicit")).isTrue();
        assertThat(this.<Boolean>read(created, "$.isFree")).isTrue();

        for (ClipRole role : ClipRole.values()) {
            MvcTestResult upload = post("/v1/admin/packs/" + packId + "/clips", """
                    {"role": "%s", "caption": "Line for %s", "contentType": "video/mp4", "sizeBytes": 4200000}""".formatted(role, role));
            assertThat(upload).hasStatusOk();
            assertThat(this.<String>read(upload, "$.uploadUrl")).startsWith("https://s3.test/packs/" + packId + "/");
            assertThat(this.<String>read(upload, "$.headers['Content-Type']")).isEqualTo("video/mp4");

            MvcTestResult completed = post("/v1/admin/clips/" + read(upload, "$.clipId") + "/complete", null);
            assertThat(completed).hasStatusOk();
            assertThat(this.<String>read(completed, "$.status")).isEqualTo("PROCESSING");
        }
        await().atMost(Duration.ofSeconds(10)).until(() -> this.<List<String>>read(
                mvc.get().uri("/v1/admin/packs/{id}", packId).header(HttpHeaders.AUTHORIZATION, bearer()).exchange(), "$.clips[*].status"),
                statuses -> statuses.equals(List.of("READY", "READY", "READY", "READY")));

        MvcTestResult published = post("/v1/admin/packs/" + packId + "/publish", null);

        assertThat(published).hasStatusOk();
        assertThat(this.<String>read(published, "$.status")).isEqualTo("PUBLISHED");
        assertThat(this.<List<String>>read(published, "$.clips[*].role")).containsExactly("START", "REMINDER", "NAG", "CONGRATS");
        MvcTestResult catalog = mvc.get().uri("/v1/packs").exchange();
        assertThat(this.<List<String>>read(catalog, "$[?(@.id == '" + packId + "')].title")).containsExactly(title);
    }

    @Test
    void theAdminSeesDraftsWithTheirClipStatus() throws Exception {
        String packId = read(post("/v1/admin/packs", """
                {"title": "Draft %s", "description": "Not yet", "isExplicit": false}""".formatted(word())), "$.id");
        post("/v1/admin/packs/" + packId + "/clips", """
                {"role": "NAG", "caption": "Still waiting.", "contentType": "video/quicktime", "sizeBytes": 1000}""");

        MvcTestResult one = mvc.get().uri("/v1/admin/packs/{id}", packId).header(HttpHeaders.AUTHORIZATION, bearer()).exchange();
        MvcTestResult all = mvc.get().uri("/v1/admin/packs").header(HttpHeaders.AUTHORIZATION, bearer()).exchange();

        assertThat(one).hasStatusOk();
        assertThat(this.<String>read(one, "$.status")).isEqualTo("DRAFT");
        assertThat(this.<List<String>>read(one, "$.clips[*].role")).containsExactly("NAG");
        assertThat(this.<List<String>>read(one, "$.clips[*].status")).containsExactly("UPLOADING");
        assertThat(this.<List<String>>read(all, "$[*].id")).contains(packId);
        assertThat(one.getResponse().getContentAsString()).doesNotContain("Key");
    }

    @Test
    void publishingBeforeEveryClipIsReadyIsAConflict() throws Exception {
        String packId = read(post("/v1/admin/packs", """
                {"title": "Early %s", "description": "Too soon", "isExplicit": false}""".formatted(word())), "$.id");

        assertError(post("/v1/admin/packs/" + packId + "/publish", null), HttpStatus.CONFLICT, "PACK_CLIPS_NOT_READY");
    }

    @Test
    void badRequestsAreValidationErrors() throws Exception {
        assertError(post("/v1/admin/packs", """
                {"title": "  ", "description": "Blank title", "isExplicit": false}"""), HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED");
        assertError(post("/v1/admin/packs", """
                {"title": "No description"}"""), HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED");
        String packId = read(post("/v1/admin/packs", """
                {"title": "Valid %s", "description": "Fine", "isExplicit": false}""".formatted(word())), "$.id");
        assertError(post("/v1/admin/packs/" + packId + "/clips", """
                {"role": "WHISPER", "caption": "Hm.", "contentType": "video/mp4", "sizeBytes": 1000}"""),
                HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED");
        assertError(post("/v1/admin/packs/" + packId + "/clips", """
                {"role": "START", "caption": "Hm.", "contentType": "video/mp4"}"""), HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED");
        assertError(post("/v1/admin/packs/" + packId + "/clips", """
                {"role": "START", "caption": "Hm.", "contentType": "image/png", "sizeBytes": 1000}"""),
                HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_FILE_TYPE");
    }

    @Test
    void unknownPacksAndClipsAre404() throws Exception {
        assertError(mvc.get().uri("/v1/admin/packs/{id}", UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, bearer()).exchange(),
                HttpStatus.NOT_FOUND, "PACK_NOT_FOUND");
        assertError(post("/v1/admin/clips/" + UUID.randomUUID() + "/complete", null), HttpStatus.NOT_FOUND, "CLIP_NOT_FOUND");
    }

    @Test
    void everyAdminEndpointNeedsTheAdminToken() throws Exception {
        assertError(mvc.get().uri("/v1/admin/packs").exchange(), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        assertError(mvc.get().uri("/v1/admin/packs").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token() + "x").exchange(),
                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        assertError(mvc.post().uri("/v1/admin/packs").header(HttpHeaders.AUTHORIZATION, admin.token())
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange(), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        assertError(mvc.post().uri("/v1/admin/packs/{id}/publish", UUID.randomUUID()).exchange(), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    }

    @Test
    void theAdminTokenDoesNotSignIntoUserEndpoints() throws Exception {
        assertError(mvc.get().uri("/v1/me").header(HttpHeaders.AUTHORIZATION, bearer()).exchange(), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    }

    private MvcTestResult post(String uri, String json) {
        var request = mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer());
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return request.exchange();
    }

    private String bearer() {
        return "Bearer " + admin.token();
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
