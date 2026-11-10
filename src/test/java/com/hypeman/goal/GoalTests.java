package com.hypeman.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.hypeman.TestcontainersConfiguration;
import com.hypeman.auth.service.TokenService;
import com.hypeman.user.model.User;
import com.hypeman.user.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/** Goal sync, end to end: devices push what they changed and pull what changed elsewhere. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class GoalTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Autowired
    JsonMapper json;

    @Test
    void pushedGoalsComeBackOnAFullPull() throws Exception {
        UUID user = newUser();
        UUID legDay = UUID.randomUUID();
        Map<String, Object> goal = goal(legDay, "Leg day", 0, now());
        goal.put("reminderAt", "2026-11-20T07:30:00Z");
        goal.put("motivatorId", "default");

        assertThat(push(user, goal, goal(UUID.randomUUID(), "Call mom", 1, now()))).hasStatus(HttpStatus.NO_CONTENT);

        MvcTestResult pulled = pull(user, null);
        assertThat(pulled).hasStatusOk();
        assertThat(this.<List<String>>read(pulled, "$.goals[*].title")).containsExactly("Leg day", "Call mom");
        assertThat(this.<String>read(pulled, "$.goals[0].id")).isEqualTo(legDay.toString());
        assertThat(Instant.parse(read(pulled, "$.goals[0].reminderAt"))).isEqualTo(Instant.parse("2026-11-20T07:30:00Z"));
        assertThat(this.<String>read(pulled, "$.goals[0].motivatorId")).isEqualTo("default");
        assertThat(this.<Boolean>read(pulled, "$.goals[0].deleted")).isFalse();
    }

    @Test
    void aPullSinceTheCursorHasWhatChangedAfterIt() throws Exception {
        UUID user = newUser();
        push(user, goal(UUID.randomUUID(), "Leg day", 0, now()));
        String cursor = read(pull(user, null), "$.cursor");

        push(user, goal(UUID.randomUUID(), "Call mom", 1, now()));

        assertThat(this.<List<String>>read(pull(user, cursor), "$.goals[*].title")).contains("Call mom");
        assertThat(this.<List<String>>read(pull(user, Instant.now().plus(1, ChronoUnit.HOURS).toString()), "$.goals")).isEmpty();
    }

    @Test
    void theNewestChangeWinsWhateverOrderTheyArriveIn() throws Exception {
        UUID user = newUser();
        UUID id = UUID.randomUUID();
        Instant earlier = now().minus(5, ChronoUnit.MINUTES);
        push(user, goal(id, "Leg day, heavy", 0, now()));

        push(user, goal(id, "Leg day", 0, earlier));

        assertThat(this.<List<String>>read(pull(user, null), "$.goals[*].title")).containsExactly("Leg day, heavy");
    }

    @Test
    void aDeletedGoalComesBackAsDeletedWithNothingElse() throws Exception {
        UUID user = newUser();
        UUID id = UUID.randomUUID();
        push(user, goal(id, "Leg day", 0, now().minusSeconds(60)));
        String cursor = read(pull(user, null), "$.cursor");

        push(user, deletion(id, now()));

        MvcTestResult changed = pull(user, cursor);
        assertThat(this.<List<String>>read(changed, "$.goals[*].id")).containsExactly(id.toString());
        assertThat(this.<Boolean>read(changed, "$.goals[0].deleted")).isTrue();
        assertThat(this.<Object>read(changed, "$.goals[0].title")).isNull();
        assertThat(this.<List<Object>>read(pull(user, null), "$.goals")).as("a full pull skips deleted goals").isEmpty();
    }

    @Test
    void aDeletedGoalStaysDeleted() throws Exception {
        UUID user = newUser();
        UUID id = UUID.randomUUID();
        push(user, deletion(id, now().minusSeconds(60)));

        push(user, goal(id, "Leg day", 0, now()));

        assertThat(this.<List<Object>>read(pull(user, null), "$.goals")).isEmpty();
    }

    @Test
    void goalsNeedSignIn() throws Exception {
        assertError(mvc.get().uri("/v1/goals").exchange(), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        assertError(mvc.put().uri("/v1/goals").contentType(MediaType.APPLICATION_JSON).content("{\"goals\": []}").exchange(),
                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    }

    @SafeVarargs
    private MvcTestResult push(UUID user, Map<String, Object>... goals) throws Exception {
        return mvc.put().uri("/v1/goals").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("goals", List.of(goals)))).exchange();
    }

    private MvcTestResult pull(UUID user, String since) {
        return since == null
                ? mvc.get().uri("/v1/goals").header("Authorization", bearer(user)).exchange()
                : mvc.get().uri("/v1/goals?since={since}", since).header("Authorization", bearer(user)).exchange();
    }

    private static Map<String, Object> goal(UUID id, String title, int sortIndex, Instant changedAt) {
        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("id", id.toString());
        goal.put("title", title);
        goal.put("sortIndex", sortIndex);
        goal.put("changedAt", changedAt.toString());
        return goal;
    }

    private static Map<String, Object> deletion(UUID id, Instant changedAt) {
        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("id", id.toString());
        goal.put("changedAt", changedAt.toString());
        goal.put("deleted", true);
        return goal;
    }

    /** Postgres keeps microseconds; the JVM's clock may have more. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private UUID newUser() {
        return users.save(new User()).getId();
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
