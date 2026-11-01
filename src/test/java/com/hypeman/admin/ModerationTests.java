package com.hypeman.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.hypeman.TestcontainersConfiguration;
import com.hypeman.auth.config.AdminProperties;
import com.hypeman.auth.service.TokenService;
import com.hypeman.user.model.User;
import com.hypeman.user.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/** Moderation (AD-06) through the admin API: bans and the audit log. */
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

    @Test
    void banningSignsTheUserOutEverywhereAndGoesInTheAuditLog() throws Exception {
        UUID troll = newUser("Troll");
        String session = bearer(troll);

        MvcTestResult banned = adminPost("/v1/admin/users/" + troll + "/ban", Map.of("reason", "  Repeated harassment "));

        assertThat(banned).hasStatusOk();
        assertThat(this.<String>read(banned, "$.bannedAt")).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null", Integer.class, troll)).isZero();
        assertThat(audit("user:" + troll)).containsExactly("user.ban: Repeated harassment");
        assertError(mvc.get().uri("/v1/me").header("Authorization", session).exchange(), HttpStatus.FORBIDDEN, "ACCOUNT_BANNED");
    }

    @Test
    void moderationNeedsTheAdminTokenAndRealTargets() throws Exception {
        UUID user = newUser("Sam");

        assertThat(mvc.get().uri("/v1/admin/audit").header("Authorization", bearer(user)).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/v1/admin/audit").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertError(adminPost("/v1/admin/users/" + UUID.randomUUID() + "/ban", Map.of("reason", "Spam")), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        assertError(adminPost("/v1/admin/users/" + user + "/ban", Map.of("reason", " ")), HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED");
    }

    private List<String> audit(String target) throws Exception {
        List<Map<String, Object>> entries = read(adminGet("/v1/admin/audit?limit=500"), "$.entries[?(@.target == '" + target + "')]");
        return entries.stream().map(entry -> entry.get("action") + ": " + entry.get("detail")).toList();
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
