package com.hypeman.yell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hypeman.TestcontainersConfiguration;
import com.hypeman.auth.service.TokenService;
import com.hypeman.media.service.ClipTranscoder;
import com.hypeman.media.service.ClipTranscoder.Probe;
import com.hypeman.media.service.MediaStorage;
import com.hypeman.media.service.MediaStorage.PresignedUpload;
import com.hypeman.push.model.PushMessage;
import com.hypeman.push.service.PushService;
import com.hypeman.user.model.User;
import com.hypeman.user.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/** Invites, friends, blocks and yells, end to end through the API, with S3, ffprobe and APNs faked. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class FriendsAndYellsTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @MockitoBean
    MediaStorage media;

    @MockitoBean
    ClipTranscoder transcoder;

    @MockitoBean
    PushService push;

    @BeforeEach
    void fakeS3AndFfprobe() {
        when(transcoder.probe(any())).thenReturn(new Probe(4_200, true, true));
        when(media.presignUpload(anyString(), anyString(), anyLong())).thenAnswer(call ->
                new PresignedUpload(URI.create("https://s3.test/" + call.getArgument(0)), Map.of("Content-Type", call.getArgument(1)), Instant.now()));
        when(media.presignDownload(anyString())).thenAnswer(call -> URI.create("https://s3.test/" + call.getArgument(0) + "?signed"));
        when(media.sizeOf(anyString())).thenReturn(40_000L);
    }

    @Test
    void acceptingAnInviteMakesTwoPeopleFriends() throws Exception {
        UUID inviter = newUser("Coach");
        UUID friend = newUser("Sam");

        MvcTestResult invite = post(inviter, "/v1/invites", null);
        String code = read(invite, "$.code");
        MvcTestResult accepted = post(friend, "/v1/invites/" + code.toLowerCase() + "/accept", null);

        assertThat(code).matches("[A-HJ-KM-NP-Z2-9]{8}");
        assertThat(this.<String>read(invite, "$.link")).endsWith(code);
        assertThat(accepted).hasStatusOk();
        assertThat(this.<String>read(accepted, "$.friend.displayName")).isEqualTo("Coach");
        assertThat(friendNames(inviter)).containsExactly("Sam");
        assertThat(friendNames(friend)).containsExactly("Coach");
    }

    @Test
    void anInviteWorksOnce() throws Exception {
        UUID inviter = newUser("Coach");
        UUID first = newUser("Sam");
        String code = read(post(inviter, "/v1/invites", null), "$.code");
        post(first, "/v1/invites/" + code + "/accept", null);

        assertThat(post(first, "/v1/invites/" + code + "/accept", null)).hasStatusOk();
        assertError(post(newUser("Late"), "/v1/invites/" + code + "/accept", null), HttpStatus.CONFLICT, "INVITE_USED");
    }

    @Test
    void invitesThatCantBeUsed() throws Exception {
        UUID inviter = newUser("Coach");
        String code = read(post(inviter, "/v1/invites", null), "$.code");
        String expired = read(post(inviter, "/v1/invites", null), "$.code");
        jdbc.update("update friend_invites set expires_at = now() - interval '1 minute' where code = ?", expired);

        assertError(post(inviter, "/v1/invites/" + code + "/accept", null), HttpStatus.UNPROCESSABLE_CONTENT, "INVITE_OWN");
        assertError(post(newUser("Sam"), "/v1/invites/" + expired + "/accept", null), HttpStatus.GONE, "INVITE_EXPIRED");
        assertError(post(newUser("Sam"), "/v1/invites/ZZZZZZZZ/accept", null), HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND");
    }

    @Test
    void aYellReachesAFriendsInbox() throws Exception {
        UUID sender = newUser("Coach");
        UUID friend = newUser("Sam");
        befriend(sender, friend);

        MvcTestResult upload = upload(sender, true);
        UUID yellId = UUID.fromString(read(upload, "$.yellId"));
        MvcTestResult sent = send(sender, yellId, friend);

        assertThat(this.<String>read(upload, "$.audioUpload.url")).contains("yells/" + yellId + "/audio.m4a");
        assertThat(this.<String>read(upload, "$.videoUpload.url")).contains("yells/" + yellId + "/video.mp4");
        assertThat(sent).hasStatusOk();
        assertThat(this.<Integer>read(sent, "$.sendsLeftThisMonth")).isEqualTo(4);
        MvcTestResult inbox = get(friend, "/v1/inbox");
        assertThat(this.<String>read(inbox, "$.deliveries[0].yellId")).isEqualTo(yellId.toString());
        assertThat(this.<String>read(inbox, "$.deliveries[0].sender.displayName")).isEqualTo("Coach");
        assertThat(this.<String>read(inbox, "$.deliveries[0].caption")).isEqualTo("Get up. Do it. Now.");
        assertThat(this.<String>read(inbox, "$.deliveries[0].role")).isEqualTo("START");
        assertThat(this.<String>read(inbox, "$.deliveries[0].audioUrl")).endsWith("audio.m4a?signed");
        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(push, timeout(5_000)).send(eq(List.of(friend)), message.capture());
        assertThat(message.getValue().title()).isEqualTo("Coach yelled at you");
        await().atMost(Duration.ofSeconds(5)).until(() -> this.<Integer>read(get(friend, "/v1/inbox"), "$.deliveries[0].durationMs") != null);
    }

    @Test
    void sendingAgainDeliversOnceAndCountsOnce() throws Exception {
        UUID sender = newUser("Coach");
        UUID friend = newUser("Sam");
        befriend(sender, friend);
        UUID yellId = UUID.fromString(read(upload(sender, false), "$.yellId"));

        send(sender, yellId, friend);
        MvcTestResult again = send(sender, yellId, friend);

        assertThat(again).hasStatusOk();
        assertThat(this.<Integer>read(again, "$.sendsLeftThisMonth")).isEqualTo(4);
        assertThat(this.<List<String>>read(get(friend, "/v1/inbox"), "$.deliveries[*].yellId")).containsExactly(yellId.toString());
    }

    @Test
    void onlyFriendsCanBeYelledAt() throws Exception {
        UUID sender = newUser("Coach");
        UUID stranger = newUser("Sam");
        UUID yellId = UUID.fromString(read(upload(sender, false), "$.yellId"));

        assertError(send(sender, yellId, stranger), HttpStatus.FORBIDDEN, "NOT_FRIENDS");
    }

    @Test
    void theFreeCapIsFiveAMonthAndProHasNone() throws Exception {
        UUID sender = newUser("Coach");
        UUID friend = newUser("Sam");
        befriend(sender, friend);
        for (int i = 0; i < 5; i++) {
            assertThat(send(sender, UUID.fromString(read(upload(sender, false), "$.yellId")), friend)).hasStatusOk();
        }
        UUID sixth = UUID.fromString(read(upload(sender, false), "$.yellId"));

        assertError(send(sender, sixth, friend), HttpStatus.PAYMENT_REQUIRED, "SEND_CAP_REACHED");
        assertThat(this.<Integer>read(get(sender, "/v1/me"), "$.sendsLeftThisMonth")).isZero();

        jdbc.update("update users set pro_until = ? where id = ?", Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)), sender);
        MvcTestResult asPro = send(sender, sixth, friend);
        assertThat(asPro).hasStatusOk();
        assertThat(this.<Object>read(asPro, "$.sendsLeftThisMonth")).isNull();
    }

    @Test
    void aYellNeedsItsUploadsFinished() throws Exception {
        UUID sender = newUser("Coach");
        UUID friend = newUser("Sam");
        befriend(sender, friend);
        UUID yellId = UUID.fromString(read(upload(sender, false), "$.yellId"));
        when(media.sizeOf("yells/" + yellId + "/audio.m4a")).thenReturn(-1L);

        assertError(send(sender, yellId, friend), HttpStatus.CONFLICT, "UPLOAD_NOT_FOUND");
    }

    @Test
    void uploadsAreCapped() throws Exception {
        UUID sender = newUser("Coach");

        assertError(post(sender, "/v1/yells/upload-urls", Map.of("role", "NAG", "caption", "Up!", "audioSize", 3_000_000)),
                HttpStatus.UNPROCESSABLE_CONTENT, "UPLOAD_TOO_LARGE");
        assertError(post(sender, "/v1/yells/upload-urls", Map.of("role", "NAG", "caption", "Up!", "audioSize", 40_000,
                "videoSize", 1_000_000, "videoContentType", "video/webm")), HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_FILE_TYPE");
    }

    @Test
    void aYellOverTenSecondsIsTakenDownAndNotPushed() throws Exception {
        when(transcoder.probe(any())).thenReturn(new Probe(14_000, false, true));
        UUID sender = newUser("Coach");
        UUID friend = newUser("Sam");
        befriend(sender, friend);

        send(sender, UUID.fromString(read(upload(sender, false), "$.yellId")), friend);

        await().atMost(Duration.ofSeconds(5)).until(() -> this.<List<?>>read(get(friend, "/v1/inbox"), "$.deliveries").isEmpty());
        verify(push, after(500).never()).send(any(), any());
    }

    @Test
    void savingKeepsAYellAndHidingRemovesIt() throws Exception {
        UUID sender = newUser("Coach");
        UUID friend = newUser("Sam");
        befriend(sender, friend);
        send(sender, UUID.fromString(read(upload(sender, false), "$.yellId")), friend);
        String delivery = read(get(friend, "/v1/inbox"), "$.deliveries[0].deliveryId");

        assertThat(post(friend, "/v1/inbox/" + delivery + "/save", null)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(this.<String>read(get(friend, "/v1/inbox"), "$.deliveries[0].savedAt")).isNotNull();
        assertThat(post(friend, "/v1/inbox/" + delivery + "/hide", null)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(this.<List<?>>read(get(friend, "/v1/inbox"), "$.deliveries")).isEmpty();
        assertError(post(sender, "/v1/inbox/" + delivery + "/save", null), HttpStatus.NOT_FOUND, "DELIVERY_NOT_FOUND");
    }

    @Test
    void aReportedYellDisappearsForTheReporter() throws Exception {
        UUID sender = newUser("Coach");
        UUID friend = newUser("Sam");
        befriend(sender, friend);
        UUID yellId = UUID.fromString(read(upload(sender, false), "$.yellId"));
        send(sender, yellId, friend);

        assertThat(post(friend, "/v1/reports", Map.of("yellId", yellId, "reason", "HARASSMENT"))).hasStatus(HttpStatus.CREATED);
        assertThat(post(friend, "/v1/reports", Map.of("yellId", yellId, "reason", "HARASSMENT"))).hasStatus(HttpStatus.CREATED);

        assertThat(this.<List<?>>read(get(friend, "/v1/inbox"), "$.deliveries")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from reports where yell_id = ? and status = 'OPEN'", Integer.class, yellId)).isOne();
        assertError(post(newUser("Nosy"), "/v1/reports", Map.of("yellId", yellId, "reason", "SPAM")), HttpStatus.NOT_FOUND, "YELL_NOT_FOUND");
    }

    @Test
    void blockingEndsTheFriendshipAndHidesTheirYells() throws Exception {
        UUID sender = newUser("Coach");
        UUID friend = newUser("Sam");
        befriend(sender, friend);
        send(sender, UUID.fromString(read(upload(sender, false), "$.yellId")), friend);

        assertThat(post(friend, "/v1/blocks", Map.of("userId", sender))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(friendNames(friend)).isEmpty();
        assertThat(this.<List<?>>read(get(friend, "/v1/inbox"), "$.deliveries")).isEmpty();
        assertError(send(sender, UUID.fromString(read(upload(sender, false), "$.yellId")), friend), HttpStatus.FORBIDDEN, "NOT_FRIENDS");
        String code = read(post(sender, "/v1/invites", null), "$.code");
        assertError(post(friend, "/v1/invites/" + code + "/accept", null), HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND");
    }

    @Test
    void removingAFriendStopsTheirYells() throws Exception {
        UUID sender = newUser("Coach");
        UUID friend = newUser("Sam");
        befriend(sender, friend);

        assertThat(mvc.delete().uri("/v1/friends/{id}", sender).header("Authorization", bearer(friend)).exchange())
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(friendNames(sender)).isEmpty();
        assertError(send(sender, UUID.fromString(read(upload(sender, false), "$.yellId")), friend), HttpStatus.FORBIDDEN, "NOT_FRIENDS");
    }

    @Test
    void theInboxPagesNewestFirst() throws Exception {
        UUID sender = newUser("Coach");
        UUID friend = newUser("Sam");
        befriend(sender, friend);
        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String yellId = read(upload(sender, false), "$.yellId");
            send(sender, UUID.fromString(yellId), friend);
            sent.addFirst(yellId);
        }

        MvcTestResult first = get(friend, "/v1/inbox?limit=2");
        String cursor = read(first, "$.nextCursor");
        MvcTestResult second = get(friend, "/v1/inbox?limit=2&cursor=" + cursor);

        assertThat(this.<List<String>>read(first, "$.deliveries[*].yellId")).containsExactly(sent.get(0), sent.get(1));
        assertThat(this.<List<String>>read(second, "$.deliveries[*].yellId")).containsExactly(sent.get(2));
        assertThat(this.<Object>read(second, "$.nextCursor")).isNull();
        assertError(get(friend, "/v1/inbox?cursor=junk"), HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED");
    }

    @Test
    void aDeletedUserLeavesFriendListsAndInboxes() throws Exception {
        UUID sender = newUser("Coach");
        UUID friend = newUser("Sam");
        befriend(sender, friend);
        send(sender, UUID.fromString(read(upload(sender, false), "$.yellId")), friend);

        assertThat(mvc.delete().uri("/v1/me").header("Authorization", bearer(sender)).exchange()).hasStatus(HttpStatus.ACCEPTED);

        assertThat(friendNames(friend)).isEmpty();
        assertThat(this.<List<?>>read(get(friend, "/v1/inbox"), "$.deliveries")).isEmpty();
    }

    @Test
    void aDisplayNameIsWhatFriendsSee() throws Exception {
        UUID user = newUser(null);

        MvcTestResult renamed = mvc.patch().uri("/v1/me").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\": \"  Coach K  \"}").exchange();

        assertThat(this.<String>read(renamed, "$.displayName")).isEqualTo("Coach K");
        assertError(mvc.patch().uri("/v1/me").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\": \" \"}").exchange(), HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED");
    }

    private void befriend(UUID inviter, UUID friend) throws Exception {
        String code = read(post(inviter, "/v1/invites", null), "$.code");
        assertThat(post(friend, "/v1/invites/" + code + "/accept", null)).hasStatusOk();
    }

    private MvcTestResult upload(UUID sender, boolean withVideo) throws Exception {
        Map<String, Object> body = withVideo
                ? Map.of("role", "START", "caption", "Get up. Do it. Now.", "audioSize", 40_000, "videoSize", 2_000_000, "videoContentType", "video/mp4")
                : Map.of("role", "START", "caption", "Get up. Do it. Now.", "audioSize", 40_000);
        MvcTestResult result = post(sender, "/v1/yells/upload-urls", body);
        assertThat(result).hasStatusOk();
        return result;
    }

    private MvcTestResult send(UUID sender, UUID yellId, UUID... recipients) throws Exception {
        return post(sender, "/v1/yells/" + yellId + "/send", Map.of("recipientIds", List.of(recipients)));
    }

    private List<String> friendNames(UUID user) throws Exception {
        return read(get(user, "/v1/friends"), "$.friends[*].displayName");
    }

    private MvcTestResult post(UUID user, String path, Object body) throws Exception {
        var request = mvc.post().uri(path).header("Authorization", bearer(user));
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return request.exchange();
    }

    private MvcTestResult get(UUID user, String path) {
        return mvc.get().uri(path).header("Authorization", bearer(user)).exchange();
    }

    private UUID newUser(String name) {
        User user = new User();
        if (name != null) {
            user.rename(name);
        }
        return users.save(user).getId();
    }

    private String bearer(UUID user) {
        return "Bearer " + tokens.signIn(user, UUID.randomUUID()).accessToken();
    }

    private <T> T read(MvcTestResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    private void assertError(MvcTestResult result, HttpStatus status, String code) throws Exception {
        assertThat(result).hasStatus(status);
        assertThat(this.<String>read(result, "$.code")).isEqualTo(code);
    }
}
