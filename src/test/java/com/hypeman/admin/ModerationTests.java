package com.hypeman.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.hypeman.TestcontainersConfiguration;
import com.hypeman.auth.config.AdminProperties;
import com.hypeman.auth.service.TokenService;
import com.hypeman.media.service.ClipTranscoder;
import com.hypeman.media.service.ClipTranscoder.Probe;
import com.hypeman.media.service.MediaStorage;
import com.hypeman.media.service.MediaStorage.PresignedUpload;
import com.hypeman.push.service.PushService;
import com.hypeman.user.model.User;
import com.hypeman.user.repository.UserRepository;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/** The moderation queue (AD-06) through the admin API: reported yells, take-downs, dismissals, bans and the audit log. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ModerationTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Autowired
    AdminProperties admin;

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
        when(transcoder.probe(any())).thenReturn(new Probe(4_200, false, true));
        when(media.presignUpload(anyString(), anyString(), anyLong())).thenAnswer(call ->
                new PresignedUpload(URI.create("https://s3.test/" + call.getArgument(0)), Map.of("Content-Type", call.getArgument(1)), Instant.now()));
        when(media.presignDownload(anyString())).thenAnswer(call -> URI.create("https://s3.test/" + call.getArgument(0) + "?signed"));
        when(media.sizeOf(anyString())).thenReturn(40_000L);
    }

    @Test
    void aReportedYellWaitsInTheQueueWithItsReportsAndSender() throws Exception {
        UUID sender = newUser("Troll");
        UUID first = newUser("Sam");
        UUID second = newUser("Alex");
        UUID yellId = sendYell(sender, first, second);

        report(first, yellId, "HARASSMENT");
        report(second, yellId, "HATE");

        Map<String, Object> item = queueItem(yellId);
        assertThat(item).containsEntry("caption", "Get up. Do it. Now.").containsEntry("status", "READY");
        assertThat((String) item.get("audioUrl")).endsWith("audio.m4a?signed");
        assertThat(JsonPath.<List<String>>read(item, "$.reports[*].reason")).containsExactly("HARASSMENT", "HATE");
        assertThat(JsonPath.<String>read(item, "$.sender.displayName")).isEqualTo("Troll");
        assertThat(JsonPath.<Integer>read(item, "$.sender.takedowns")).isZero();
    }

    @Test
    void takingAYellDownRemovesItForEveryoneAndClosesItsReports() throws Exception {
        UUID sender = newUser("Troll");
        UUID reporter = newUser("Sam");
        UUID bystander = newUser("Alex");
        UUID yellId = sendYell(sender, reporter, bystander);
        report(reporter, yellId, "HARASSMENT");

        MvcTestResult takenDown = adminPost("/v1/admin/yells/" + yellId + "/take-down", Map.of("note", "Abusive"));

        assertThat(takenDown).hasStatusOk();
        assertThat(this.<String>read(takenDown, "$.status")).isEqualTo("REMOVED");
        assertThat(this.<Integer>read(takenDown, "$.reportsClosed")).isOne();
        assertThat(inbox(bystander)).isEmpty();
        assertThat(queueItem(yellId)).isNull();
        assertThat(audit("yell:" + yellId)).containsExactly("yell.take_down: Abusive");

        UUID next = sendYell(sender, reporter);
        report(reporter, next, "SPAM");
        assertThat(JsonPath.<Integer>read(queueItem(next), "$.sender.takedowns")).isOne();
    }

    @Test
    void dismissingKeepsTheYellUpForOthers() throws Exception {
        UUID sender = newUser("Coach");
        UUID reporter = newUser("Sam");
        UUID bystander = newUser("Alex");
        UUID yellId = sendYell(sender, reporter, bystander);
        report(reporter, yellId, "OTHER");

        MvcTestResult dismissed = adminPost("/v1/admin/yells/" + yellId + "/dismiss", null);

        assertThat(dismissed).hasStatusOk();
        assertThat(this.<String>read(dismissed, "$.status")).isEqualTo("READY");
        assertThat(inbox(bystander)).hasSize(1);
        assertThat(inbox(reporter)).isEmpty();
        assertThat(queueItem(yellId)).isNull();
        assertThat(jdbc.queryForObject("select status from reports where yell_id = ?", String.class, yellId)).isEqualTo("DISMISSED");
    }

    @Test
    void banningTakesDownEveryYellAndDropsThemFromFriendLists() throws Exception {
        UUID troll = newUser("Troll");
        UUID friend = newUser("Sam");
        UUID reported = sendYell(troll, friend);
        sendYell(troll, friend);
        report(friend, reported, "HARASSMENT");

        MvcTestResult banned = adminPost("/v1/admin/users/" + troll + "/ban", Map.of("reason", "  Repeated harassment "));

        assertThat(banned).hasStatusOk();
        assertThat(this.<String>read(banned, "$.bannedAt")).isNotNull();
        assertThat(inbox(friend)).isEmpty();
        assertThat(this.<List<?>>read(get(friend, "/v1/friends"), "$.friends")).isEmpty();
        assertThat(queueItem(reported)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null", Integer.class, troll)).isZero();
        assertThat(audit("user:" + troll)).containsExactly("user.ban: Repeated harassment");
        assertError(post(troll, "/v1/invites", null), HttpStatus.FORBIDDEN, "ACCOUNT_BANNED");
    }

    @Test
    void moderationNeedsTheAdminTokenAndRealTargets() throws Exception {
        UUID user = newUser("Sam");

        assertThat(get(user, "/v1/admin/reports")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/v1/admin/reports").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertError(adminPost("/v1/admin/yells/" + UUID.randomUUID() + "/take-down", null), HttpStatus.NOT_FOUND, "YELL_NOT_FOUND");
        assertError(adminPost("/v1/admin/users/" + UUID.randomUUID() + "/ban", Map.of("reason", "Spam")), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        assertError(adminPost("/v1/admin/users/" + user + "/ban", Map.of("reason", " ")), HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED");
    }

    private UUID sendYell(UUID sender, UUID... recipients) throws Exception {
        for (UUID recipient : recipients) {
            if (jdbc.queryForObject("select count(*) from friendships where (user_a = ? and user_b = ?) or (user_a = ? and user_b = ?)",
                    Integer.class, sender, recipient, recipient, sender) == 0) {
                String code = read(post(sender, "/v1/invites", null), "$.code");
                assertThat(post(recipient, "/v1/invites/" + code + "/accept", null)).hasStatusOk();
            }
        }
        MvcTestResult upload = post(sender, "/v1/yells/upload-urls", Map.of("role", "START", "caption", "Get up. Do it. Now.", "audioSize", 40_000));
        UUID yellId = UUID.fromString(read(upload, "$.yellId"));
        assertThat(post(sender, "/v1/yells/" + yellId + "/send", Map.of("recipientIds", List.of(recipients)))).hasStatusOk();
        return yellId;
    }

    private void report(UUID reporter, UUID yellId, String reason) throws Exception {
        assertThat(post(reporter, "/v1/reports", Map.of("yellId", yellId, "reason", reason))).hasStatus(HttpStatus.CREATED);
    }

    /** The yell's place in the queue, or null if it isn't waiting there. */
    private Map<String, Object> queueItem(UUID yellId) throws Exception {
        List<Map<String, Object>> items = read(adminGet("/v1/admin/reports?limit=200"), "$.items[?(@.yellId == '" + yellId + "')]");
        return items.isEmpty() ? null : items.getFirst();
    }

    private List<String> audit(String target) throws Exception {
        List<Map<String, Object>> entries = read(adminGet("/v1/admin/audit?limit=500"), "$.entries[?(@.target == '" + target + "')]");
        return entries.stream().map(entry -> entry.get("action") + ": " + entry.get("detail")).toList();
    }

    private List<?> inbox(UUID user) throws Exception {
        return read(get(user, "/v1/inbox"), "$.deliveries");
    }

    private MvcTestResult adminPost(String path, Object body) throws Exception {
        var request = mvc.post().uri(path).header("Authorization", "Bearer " + admin.token());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return request.exchange();
    }

    private MvcTestResult adminGet(String path) {
        return mvc.get().uri(path).header("Authorization", "Bearer " + admin.token()).exchange();
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
        user.rename(name);
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
