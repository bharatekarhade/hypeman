package com.hypeman.pack.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.hypeman.TestcontainersConfiguration;
import com.hypeman.auth.config.AdminProperties;
import com.hypeman.auth.service.TokenService;
import com.hypeman.media.service.ClipTranscoder;
import com.hypeman.media.service.ClipTranscoder.Outputs;
import com.hypeman.media.service.ClipTranscoder.Probe;
import com.hypeman.media.service.MediaStorage;
import com.hypeman.media.service.MediaStorage.PresignedUpload;
import com.hypeman.pack.model.ClipRole;
import com.hypeman.pack.model.ClipStatus;
import com.hypeman.pack.model.Pack;
import com.hypeman.pack.repository.ClipRepository;
import com.hypeman.pack.service.PackService;
import com.hypeman.user.model.User;
import com.hypeman.user.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The admin's download numbers per pack, from downloads made through the app's endpoint. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PackDownloadTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    PackService service;

    @Autowired
    ClipRepository clips;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

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
        when(media.presignUpload(anyString(), anyString(), anyLong())).thenAnswer(call ->
                new PresignedUpload(URI.create("https://s3.test/" + call.getArgument(0)), Map.of(), Instant.now()));
        when(media.presignDownload(anyString())).thenAnswer(call -> URI.create("https://s3.test/" + call.getArgument(0) + "?signed"));
        when(media.sizeOf(anyString())).thenReturn(4_200_000L);
    }

    @Test
    void theAdminSeesDownloadsAndPeoplePerPack() throws Exception {
        Pack pack = publishedPack();
        Pack untouched = service.createDefaultPack("Draft " + word(), "Not yet", false);
        UUID first = newUser();
        download(pack, first);
        download(pack, first);
        download(pack, newUser());

        MvcTestResult result = stats(null);

        assertThat(result).hasStatusOk();
        assertThat(this.<List<Integer>>read(result, entry(pack) + ".downloads")).containsExactly(3);
        assertThat(this.<List<Integer>>read(result, entry(pack) + ".people")).containsExactly(2);
        assertThat(this.<List<String>>read(result, entry(pack) + ".lastDownloadedAt")).doesNotContainNull();
        assertThat(this.<List<Integer>>read(result, entry(untouched) + ".downloads")).containsExactly(0);
        assertThat(this.<List<String>>read(result, entry(untouched) + ".status")).containsExactly("DRAFT");
    }

    @Test
    void aDeletedAccountsDownloadsStillCountButNotAsAPerson() throws Exception {
        Pack pack = publishedPack();
        UUID user = newUser();
        download(pack, user);

        users.deleteById(user);

        MvcTestResult result = stats(null);
        assertThat(this.<List<Integer>>read(result, entry(pack) + ".downloads")).containsExactly(1);
        assertThat(this.<List<Integer>>read(result, entry(pack) + ".people")).containsExactly(0);
    }

    @Test
    void sinceLeavesOutEarlierDownloads() throws Exception {
        Pack pack = publishedPack();
        download(pack, newUser());

        MvcTestResult result = stats(Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(this.<List<Integer>>read(result, entry(pack) + ".downloads")).containsExactly(0);
    }

    @Test
    void downloadNumbersNeedTheAdminToken() throws Exception {
        UUID user = newUser();
        String userToken = "Bearer " + tokens.signIn(user, UUID.randomUUID()).accessToken();

        assertThat(mvc.get().uri("/v1/admin/packs/downloads").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/v1/admin/packs/downloads").header(HttpHeaders.AUTHORIZATION, userToken).exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private MvcTestResult stats(Instant since) {
        var request = since == null
                ? mvc.get().uri("/v1/admin/packs/downloads")
                : mvc.get().uri("/v1/admin/packs/downloads?since={since}", since.toString());
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()).exchange();
    }

    private void download(Pack pack, UUID user) {
        String token = tokens.signIn(user, UUID.randomUUID()).accessToken();
        assertThat(mvc.post().uri("/v1/packs/{id}/media", pack.getId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange())
                .hasStatusOk();
    }

    private static String entry(Pack pack) {
        return "$[?(@.packId == '" + pack.getId() + "')]";
    }

    private Pack publishedPack() {
        Pack pack = service.createDefaultPack("Coach " + word(), "Yells at you", false);
        for (ClipRole role : ClipRole.values()) {
            UUID clipId = service.requestClipUpload(pack.getId(), null, role, "Line for " + role, "video/mp4", 4_200_000).clipId();
            service.completeClipUpload(clipId);
            await().atMost(Duration.ofSeconds(10)).until(() -> clips.findById(clipId).orElseThrow().getStatus() == ClipStatus.READY);
        }
        return service.publish(pack.getId());
    }

    private UUID newUser() {
        return users.save(new User()).getId();
    }

    private static String word() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private <T> T read(MvcTestResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }
}
