package com.hypeman.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import com.hypeman.TestcontainersConfiguration;
import com.hypeman.auth.config.AdminProperties;
import com.hypeman.auth.config.AuthProperties;
import com.hypeman.auth.repository.DeviceRepository;
import com.hypeman.auth.service.IdentityTokenVerifier;
import com.hypeman.user.model.IdentityProvider;
import com.hypeman.user.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Sign-in end to end on real Postgres. Apple and Google are stood in for by a key of our own: the verifier checks
 * signature, issuer, audience, expiry and nonce exactly as in production, against that key instead of theirs.
 */
@Import({TestcontainersConfiguration.class, AuthFlowTests.FakeProviders.class})
@SpringBootTest(properties = "hypeman.auth.google.client-ids=google-client-id")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AuthFlowTests {

    private static final String APPLE_CLIENT_ID = "com.hypeman.app";
    private static final RSAKey PROVIDER_KEY = generateKey();
    private static final NimbusJwtEncoder PROVIDER = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(PROVIDER_KEY)));
    private static final FakeAppleServer APPLE = new FakeAppleServer(APPLE_CLIENT_ID,
            subject -> providerToken("https://appleid.apple.com", APPLE_CLIENT_ID, subject, Map.of()));

    @Autowired
    MockMvcTester mvc;

    @Autowired
    UserRepository users;

    @Autowired
    DeviceRepository devices;

    @Autowired
    AdminProperties admin;

    @DynamicPropertySource
    static void appleApi(DynamicPropertyRegistry registry) {
        registry.add("hypeman.auth.apple-revocation.base-url", APPLE::baseUrl);
        registry.add("hypeman.auth.apple-revocation.team-id", () -> FakeAppleServer.TEAM_ID);
        registry.add("hypeman.auth.apple-revocation.key-id", () -> FakeAppleServer.KEY_ID);
        registry.add("hypeman.auth.apple-revocation.private-key", APPLE::privateKeyPem);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeProviders {

        @Bean
        @Primary
        IdentityTokenVerifier fakeProviderVerifier(AuthProperties properties) throws JOSEException {
            return new IdentityTokenVerifier(Map.of(
                    IdentityProvider.APPLE, IdentityTokenVerifier.decoder(properties.apple(), NimbusJwtDecoder.withPublicKey(PROVIDER_KEY.toRSAPublicKey()).build()),
                    IdentityProvider.GOOGLE, IdentityTokenVerifier.decoder(properties.google(), NimbusJwtDecoder.withPublicKey(PROVIDER_KEY.toRSAPublicKey()).build())));
        }
    }

    @Test
    void appleSignInCreatesTheUserOnceAndMeShowsThem() throws Exception {
        String subject = newSubject();
        MvcTestResult first = signInWithApple(subject, UUID.randomUUID());
        MvcTestResult second = signInWithApple(subject, UUID.randomUUID());

        assertThat(first).hasStatusOk();
        assertThat(read(second, "$.user.id")).isEqualTo(read(first, "$.user.id"));
        MvcTestResult me = mvc.get().uri("/v1/me").header(AUTHORIZATION, bearer(first)).exchange();
        assertThat(me).hasStatusOk();
        assertThat(read(me, "$.user.id")).isEqualTo(read(first, "$.user.id"));
        assertThat(read(me, "$.identities[0].provider")).isEqualTo("APPLE");
        assertThat(read(me, "$.identities[0].email")).isEqualTo(subject + "@privaterelay.appleid.com");
    }

    /** The app parses expiry as ISO 8601 text to refresh ahead of time, so it must not become epoch numbers. */
    @Test
    void tokenExpiryIsAnIsoInstant() throws Exception {
        MvcTestResult signIn = signInWithApple(newSubject(), UUID.randomUUID());

        assertThat(Instant.parse(read(signIn, "$.accessTokenExpiresAt"))).isAfter(Instant.now());
        assertThat(Instant.parse(read(refresh(read(signIn, "$.refreshToken")), "$.accessTokenExpiresAt"))).isAfter(Instant.now());
    }

    @Test
    void appleSignInRejectsATokenMintedForAnotherSignIn() throws Exception {
        String token = appleToken(newSubject(), "the-nonce-apple-saw", APPLE_CLIENT_ID);

        MvcTestResult result = post("/v1/auth/apple", """
                {"identityToken": "%s", "nonce": "a-different-nonce", "deviceId": "%s"}""".formatted(token, UUID.randomUUID()));

        assertError(result, HttpStatus.UNAUTHORIZED, "INVALID_IDENTITY_TOKEN");
    }

    @Test
    void appleSignInAcceptsThePersonalTeamBuild() throws Exception {
        String token = appleToken(newSubject(), "nonce", "com.bharatekarhade.hypeman");

        MvcTestResult result = post("/v1/auth/apple", """
                {"identityToken": "%s", "nonce": "nonce", "deviceId": "%s"}""".formatted(token, UUID.randomUUID()));

        assertThat(result).hasStatusOk();
    }

    @Test
    void appleSignInRejectsATokenForAnotherApp() throws Exception {
        String token = appleToken(newSubject(), "nonce", "com.someone.else");

        MvcTestResult result = post("/v1/auth/apple", """
                {"identityToken": "%s", "nonce": "nonce", "deviceId": "%s"}""".formatted(token, UUID.randomUUID()));

        assertError(result, HttpStatus.UNAUTHORIZED, "INVALID_IDENTITY_TOKEN");
    }

    @Test
    void googleSignInWorks() throws Exception {
        String token = providerToken("https://accounts.google.com", "google-client-id", newSubject(), Map.of("email", "bharat@example.com"));

        MvcTestResult result = post("/v1/auth/google", """
                {"idToken": "%s", "deviceId": "%s"}""".formatted(token, UUID.randomUUID()));

        assertThat(result).hasStatusOk();
        assertThat(read(result, "$.refreshToken")).isNotBlank();
    }

    @Test
    void refreshReplacesTheTokenAndAReplayedOneSignsTheDeviceOut() throws Exception {
        MvcTestResult signIn = signInWithApple(newSubject(), UUID.randomUUID());
        String original = read(signIn, "$.refreshToken");

        MvcTestResult refreshed = refresh(original);
        assertThat(refreshed).hasStatusOk();
        String replacement = read(refreshed, "$.refreshToken");
        assertThat(replacement).isNotEqualTo(original);

        assertError(refresh(original), HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSED");
        assertThat(refresh(replacement)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void simultaneousRefreshesWithOneTokenLetExactlyOneThrough() throws Exception {
        String refreshToken = read(signInWithApple(newSubject(), UUID.randomUUID()), "$.refreshToken");

        List<Integer> statuses = concurrently(8, () -> refresh(refreshToken).getResponse().getStatus());

        assertThat(statuses).containsOnlyOnce(200);
        assertThat(statuses).filteredOn(status -> status != 200).containsOnly(401);
    }

    @Test
    void simultaneousFirstSignInsWithOneAccountGetOneUser() throws Exception {
        String subject = newSubject();
        long usersBefore = users.count();

        List<String> userIds = concurrently(8, () -> read(signInWithApple(subject, UUID.randomUUID()), "$.user.id"));

        assertThat(Set.copyOf(userIds)).hasSize(1);
        assertThat(users.count()).as("the losers removed the users they made").isEqualTo(usersBefore + 1);
    }

    @Test
    void signingInAsSomeoneElseOnTheSamePhoneSignsTheFirstUserOutThere() throws Exception {
        UUID phone = UUID.randomUUID();
        String firstUsersToken = read(signInWithApple(newSubject(), phone), "$.refreshToken");

        signInWithApple(newSubject(), phone);

        assertThat(refresh(firstUsersToken)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        MvcTestResult signIn = signInWithApple(newSubject(), UUID.randomUUID());
        String refreshToken = read(signIn, "$.refreshToken");

        MvcTestResult logout = mvc.post().uri("/v1/auth/logout").header(AUTHORIZATION, bearer(signIn))
                .contentType(APPLICATION_JSON).content("{\"refreshToken\": \"%s\"}".formatted(refreshToken)).exchange();

        assertThat(logout).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(refresh(refreshToken)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void meNeedsAnAccessToken() throws Exception {
        MvcTestResult result = mvc.get().uri("/v1/me").exchange();

        assertError(result, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        assertThat(read(result, "$.requestId")).isEqualTo(result.getResponse().getHeader("X-Request-Id"));
    }

    @Test
    void deletingTheAccountRevokesTheAppleSignInSignsOutEverywhereAndFreesTheAppleAccount() throws Exception {
        String subject = newSubject();
        MvcTestResult signIn = signInWithApple(subject, UUID.randomUUID());

        assertThat(deleteAccount(signIn, "code-for-" + subject)).hasStatus(HttpStatus.ACCEPTED);

        assertThat(APPLE.revokedTokens).contains("apple-refresh-" + subject);
        assertThat(refresh(read(signIn, "$.refreshToken"))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertError(mvc.get().uri("/v1/me").header(AUTHORIZATION, bearer(signIn)).exchange(), HttpStatus.UNAUTHORIZED, "ACCOUNT_DELETED");
        MvcTestResult again = signInWithApple(subject, UUID.randomUUID());
        assertThat(read(again, "$.user.id")).isNotEqualTo(read(signIn, "$.user.id"));
    }

    @Test
    void aBannedUserIsSignedOutAndCantSignInUntilUnbanned() throws Exception {
        String subject = newSubject();
        MvcTestResult signIn = signInWithApple(subject, UUID.randomUUID());
        String userId = read(signIn, "$.user.id");

        assertThat(adminPost("/v1/admin/users/" + userId + "/ban", "{\"reason\": \"Harassment\"}")).hasStatusOk();

        assertThat(refresh(read(signIn, "$.refreshToken"))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertError(mvc.get().uri("/v1/me").header(AUTHORIZATION, bearer(signIn)).exchange(), HttpStatus.FORBIDDEN, "ACCOUNT_BANNED");
        assertError(signInWithApple(subject, UUID.randomUUID()), HttpStatus.FORBIDDEN, "ACCOUNT_BANNED");

        assertThat(adminPost("/v1/admin/users/" + userId + "/unban", null)).hasStatusOk();

        MvcTestResult again = signInWithApple(subject, UUID.randomUUID());
        assertThat(again).hasStatusOk();
        assertThat(read(again, "$.user.id")).isEqualTo(userId);
    }

    @Test
    void deletingAnAppleAccountNeedsAFreshAppleConfirmation() throws Exception {
        MvcTestResult signIn = signInWithApple(newSubject(), UUID.randomUUID());

        assertError(mvc.delete().uri("/v1/me").header(AUTHORIZATION, bearer(signIn)).exchange(), HttpStatus.UNPROCESSABLE_CONTENT, "APPLE_REAUTH_REQUIRED");

        assertThat(mvc.get().uri("/v1/me").header(AUTHORIZATION, bearer(signIn)).exchange()).hasStatusOk();
    }

    @Test
    void anotherAppleAccountsConfirmationDoesntDeleteTheAccount() throws Exception {
        MvcTestResult signIn = signInWithApple(newSubject(), UUID.randomUUID());
        String someoneElse = newSubject();

        assertError(deleteAccount(signIn, "code-for-" + someoneElse), HttpStatus.UNPROCESSABLE_CONTENT, "APPLE_ACCOUNT_MISMATCH");

        assertThat(APPLE.revokedTokens).doesNotContain("apple-refresh-" + someoneElse);
        assertThat(mvc.get().uri("/v1/me").header(AUTHORIZATION, bearer(signIn)).exchange()).hasStatusOk();
    }

    @Test
    void aConfirmationAppleRefusesDoesntDeleteTheAccount() throws Exception {
        MvcTestResult signIn = signInWithApple(newSubject(), UUID.randomUUID());

        assertError(deleteAccount(signIn, "an-expired-code"), HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_APPLE_AUTHORIZATION_CODE");

        assertThat(mvc.get().uri("/v1/me").header(AUTHORIZATION, bearer(signIn)).exchange()).hasStatusOk();
    }

    @Test
    void aGoogleAccountIsDeletedWithoutAnAppleConfirmation() throws Exception {
        String token = providerToken("https://accounts.google.com", "google-client-id", newSubject(), Map.of());
        MvcTestResult signIn = post("/v1/auth/google", """
                {"idToken": "%s", "deviceId": "%s"}""".formatted(token, UUID.randomUUID()));

        assertThat(mvc.delete().uri("/v1/me").header(AUTHORIZATION, bearer(signIn)).exchange()).hasStatus(HttpStatus.ACCEPTED);
    }

    @Test
    void aMissingDeviceIdIsAValidationError() throws Exception {
        String token = appleToken(newSubject(), "nonce", APPLE_CLIENT_ID);

        MvcTestResult result = post("/v1/auth/apple", "{\"identityToken\": \"%s\", \"nonce\": \"nonce\"}".formatted(token));

        assertError(result, HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED");
        assertThat(read(result, "$.message")).contains("deviceId");
    }

    @Test
    void aPushTokenMovesToTheDeviceThatRegistersItLast() throws Exception {
        UUID firstPhone = UUID.randomUUID();
        UUID secondPhone = UUID.randomUUID();
        String apnsToken = "apns-" + UUID.randomUUID();

        assertThat(registerPushToken(signInWithApple(newSubject(), firstPhone), apnsToken)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(registerPushToken(signInWithApple(newSubject(), secondPhone), apnsToken)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(devices.findById(firstPhone).orElseThrow().getApnsToken()).isNull();
        assertThat(devices.findById(secondPhone).orElseThrow().getApnsToken()).isEqualTo(apnsToken);
    }

    private MvcTestResult signInWithApple(String subject, UUID deviceId) {
        String token = appleToken(subject, "nonce-" + subject, APPLE_CLIENT_ID);
        return post("/v1/auth/apple", """
                {"identityToken": "%s", "nonce": "nonce-%s", "deviceId": "%s"}""".formatted(token, subject, deviceId));
    }

    private MvcTestResult refresh(String refreshToken) {
        return post("/v1/auth/refresh", "{\"refreshToken\": \"%s\"}".formatted(refreshToken));
    }

    private MvcTestResult registerPushToken(MvcTestResult signIn, String apnsToken) throws Exception {
        return mvc.post().uri("/v1/devices").header(AUTHORIZATION, bearer(signIn)).contentType(APPLICATION_JSON)
                .content("{\"apnsToken\": \"%s\", \"environment\": \"SANDBOX\"}".formatted(apnsToken)).exchange();
    }

    private MvcTestResult deleteAccount(MvcTestResult signIn, String appleAuthorizationCode) throws Exception {
        return mvc.delete().uri("/v1/me").header(AUTHORIZATION, bearer(signIn)).contentType(APPLICATION_JSON)
                .content("{\"appleAuthorizationCode\": \"%s\"}".formatted(appleAuthorizationCode)).exchange();
    }

    private MvcTestResult adminPost(String uri, String json) {
        var request = mvc.post().uri(uri).header(AUTHORIZATION, "Bearer " + admin.token());
        return json == null ? request.exchange() : request.contentType(APPLICATION_JSON).content(json).exchange();
    }

    private MvcTestResult post(String uri, String json) {
        return mvc.post().uri(uri).contentType(APPLICATION_JSON).content(json).exchange();
    }

    /** Runs the task on that many threads, released together so they hit the database at the same moment. */
    private static <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertError(MvcTestResult result, HttpStatus status, String code) throws Exception {
        assertThat(result).hasStatus(status);
        assertThat(read(result, "$.code")).isEqualTo(code);
    }

    private static String bearer(MvcTestResult signIn) throws Exception {
        return "Bearer " + read(signIn, "$.accessToken");
    }

    private static String read(MvcTestResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    private static String newSubject() {
        return "000" + UUID.randomUUID().toString().replace("-", "");
    }

    /** Apple puts the SHA-256 of the app's raw nonce in the token. */
    private static String appleToken(String subject, String rawNonce, String audience) {
        return providerToken("https://appleid.apple.com", audience, subject,
                Map.of("nonce", sha256Hex(rawNonce), "email", subject + "@privaterelay.appleid.com"));
    }

    private static String providerToken(String issuer, String audience, String subject, Map<String, Object> extraClaims) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(10, ChronoUnit.MINUTES));
        extraClaims.forEach(claims::claim);
        return PROVIDER.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build())).getTokenValue();
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
