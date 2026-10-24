package com.hypeman.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.hypeman.TestcontainersConfiguration;
import com.hypeman.auth.service.TokenService;
import com.hypeman.common.ApplicationException;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pro and single packs, end to end. The local profile accepts Xcode's unsigned StoreKit test transactions, so these are
 * made up here; one forged Sandbox transaction checks that anything claiming to be from Apple is really verified.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PurchaseTests {

    private static final String PRO = "com.hypeman.pro.monthly";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    PackService packs;

    @Autowired
    ClipRepository clips;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Autowired
    JsonMapper json;

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
    void theCatalogShowsWhatAPaidPackIsSoldAs() throws Exception {
        Pack paid = paidPack();

        MvcTestResult result = mvc.get().uri("/v1/packs").exchange();

        String pack = "$[?(@.id == '" + paid.getId() + "')]";
        assertThat(this.<List<Boolean>>read(result, pack + ".isFree")).containsExactly(false);
        assertThat(this.<List<String>>read(result, pack + ".productId")).containsExactly(paid.getProductId());
    }

    @Test
    void aPaidPackNeedsAPurchase() throws Exception {
        Pack paid = paidPack();

        assertError(mvc.post().uri("/v1/packs/{id}/media", paid.getId()).exchange(), HttpStatus.PAYMENT_REQUIRED, "PACK_NOT_PURCHASED");
        assertError(media(paid, newUser()), HttpStatus.PAYMENT_REQUIRED, "PACK_NOT_PURCHASED");
    }

    @Test
    void buyingAPackUnlocksThatPackOnly() throws Exception {
        Pack bought = paidPack();
        Pack other = paidPack();
        UUID user = newUser();

        MvcTestResult result = buy(user, transaction(bought.getProductId(), user, null, null));

        assertThat(result).hasStatusOk();
        assertThat(this.<List<String>>read(result, "$.packIds")).containsExactly(bought.getId().toString());
        assertThat(media(bought, user)).hasStatusOk();
        assertError(media(other, user), HttpStatus.PAYMENT_REQUIRED, "PACK_NOT_PURCHASED");
    }

    @Test
    void proUnlocksEveryPaidPack() throws Exception {
        UUID user = newUser();
        Instant expires = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

        MvcTestResult result = buy(user, transaction(PRO, user, expires, null));

        assertThat(result).hasStatusOk();
        assertThat(Instant.parse(read(result, "$.proUntil"))).isEqualTo(expires);
        assertThat(media(paidPack(), user)).hasStatusOk();
        assertThat(media(paidPack(), user)).hasStatusOk();
    }

    @Test
    void expiredProUnlocksNothing() throws Exception {
        UUID user = newUser();
        buy(user, transaction(PRO, user, Instant.now().minus(1, ChronoUnit.DAYS), null));

        assertError(media(paidPack(), user), HttpStatus.PAYMENT_REQUIRED, "PACK_NOT_PURCHASED");
    }

    @Test
    void reportingThePurchaseAgainChangesNothing() throws Exception {
        Pack pack = paidPack();
        UUID user = newUser();
        String transaction = transaction(pack.getProductId(), user, null, null);

        buy(user, transaction);
        MvcTestResult again = buy(user, transaction);

        assertThat(again).hasStatusOk();
        assertThat(this.<List<String>>read(again, "$.packIds")).containsExactly(pack.getId().toString());
    }

    @Test
    void entitlementsListWhatTheUserOwns() throws Exception {
        Pack pack = paidPack();
        UUID user = newUser();
        Instant expires = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        buy(user, transaction(pack.getProductId(), user, null, null));
        buy(user, transaction(PRO, user, expires, null));

        MvcTestResult result = mvc.get().uri("/v1/purchases").header("Authorization", bearer(user)).exchange();

        assertThat(result).hasStatusOk();
        assertThat(Instant.parse(read(result, "$.proUntil"))).isEqualTo(expires);
        assertThat(this.<List<String>>read(result, "$.packIds")).containsExactly(pack.getId().toString());
    }

    @Test
    void aPurchaseForAnotherAccountIsRefused() throws Exception {
        Pack pack = paidPack();
        UUID buyer = newUser();
        UUID someoneElse = newUser();

        assertError(buy(someoneElse, transaction(pack.getProductId(), buyer, null, null)), HttpStatus.FORBIDDEN, "PURCHASE_ACCOUNT_MISMATCH");
        assertError(media(pack, someoneElse), HttpStatus.PAYMENT_REQUIRED, "PACK_NOT_PURCHASED");
    }

    @Test
    void somethingWeDontSellIsRefused() throws Exception {
        UUID user = newUser();

        assertError(buy(user, transaction("com.someone.else.coins", user, null, null)), HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_PRODUCT");
    }

    @Test
    void anUnsignedTransactionClaimingToBeFromAppleIsRefused() throws Exception {
        Pack pack = paidPack();
        UUID user = newUser();
        String forged = jws(transactionClaims(pack.getProductId(), user, null, null, "Sandbox"));

        assertError(buy(user, forged), HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_TRANSACTION");
        assertError(media(pack, user), HttpStatus.PAYMENT_REQUIRED, "PACK_NOT_PURCHASED");
    }

    @Test
    void garbageIsRefused() throws Exception {
        assertError(buy(newUser(), "not-a-transaction"), HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_TRANSACTION");
    }

    @Test
    void reportingAPurchaseNeedsSignIn() throws Exception {
        MvcTestResult result = mvc.post().uri("/v1/purchases").contentType(MediaType.APPLICATION_JSON)
                .content("{\"signedTransaction\": \"x\"}").exchange();

        assertError(result, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    }

    @Test
    void aRefundLocksThePackAgain() throws Exception {
        Pack pack = paidPack();
        UUID user = newUser();
        String originalId = "refund-" + UUID.randomUUID();
        buy(user, transaction(pack.getProductId(), user, null, null, originalId));

        assertThat(notify(transaction(pack.getProductId(), user, null, Instant.now(), originalId))).hasStatusOk();

        assertError(media(pack, user), HttpStatus.PAYMENT_REQUIRED, "PACK_NOT_PURCHASED");
        MvcTestResult owned = mvc.get().uri("/v1/purchases").header("Authorization", bearer(user)).exchange();
        assertThat(this.<List<String>>read(owned, "$.packIds")).isEmpty();
    }

    @Test
    void aRenewalExtendsProAndAnOlderPeriodArrivingLateDoesNotShortenIt() throws Exception {
        UUID user = newUser();
        Instant firstPeriod = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant renewed = firstPeriod.plus(30, ChronoUnit.DAYS);
        buy(user, transaction(PRO, user, firstPeriod, null));

        notify(transaction(PRO, user, renewed, null));
        notify(transaction(PRO, user, firstPeriod, null));

        assertThat(users.findById(user).orElseThrow().getProUntil()).isEqualTo(renewed);
    }

    @Test
    void aRefundedSubscriptionEndsPro() throws Exception {
        UUID user = newUser();
        Instant expires = Instant.now().plus(30, ChronoUnit.DAYS);
        String period = "period-" + UUID.randomUUID();
        buy(user, transaction(PRO, user, expires, null, period, period, Instant.now()));

        notify(transaction(PRO, user, expires, Instant.now(), period, period, Instant.now()));

        assertError(media(paidPack(), user), HttpStatus.PAYMENT_REQUIRED, "PACK_NOT_PURCHASED");
    }

    @Test
    void replayingAPurchaseSignedBeforeItsRefundKeepsThePackLocked() throws Exception {
        Pack pack = paidPack();
        UUID user = newUser();
        String originalId = "refund-" + UUID.randomUUID();
        String beforeRefund = transaction(pack.getProductId(), user, null, null, originalId);
        buy(user, beforeRefund);
        notify(transaction(pack.getProductId(), user, null, Instant.now(), originalId));

        buy(user, beforeRefund);
        notify(beforeRefund);

        assertError(media(pack, user), HttpStatus.PAYMENT_REQUIRED, "PACK_NOT_PURCHASED");
    }

    @Test
    void aLateRefundOfAnEarlierPurchaseDoesNotLockAPackBoughtAgain() throws Exception {
        Pack pack = paidPack();
        UUID user = newUser();
        Instant firstBought = Instant.now().minus(10, ChronoUnit.DAYS);
        String first = "first-" + UUID.randomUUID();
        String again = "again-" + UUID.randomUUID();
        buy(user, transaction(pack.getProductId(), user, null, null, first, first, firstBought));
        notify(transaction(pack.getProductId(), user, null, firstBought.plus(1, ChronoUnit.DAYS), first, first, firstBought));
        buy(user, transaction(pack.getProductId(), user, null, null, again, again, Instant.now()));

        notify(transaction(pack.getProductId(), user, null, firstBought.plus(1, ChronoUnit.DAYS), first, first, firstBought));
        buy(user, transaction(pack.getProductId(), user, null, null, first, first, firstBought));

        assertThat(media(pack, user)).hasStatusOk();
    }

    @Test
    void replayingASubscriptionPeriodSignedBeforeItsRefundDoesNotRestorePro() throws Exception {
        UUID user = newUser();
        Instant expires = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant refunded = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String period = "period-" + UUID.randomUUID();
        String beforeRefund = transaction(PRO, user, expires, null, period, period, Instant.now());
        buy(user, beforeRefund);
        notify(transaction(PRO, user, expires, refunded, period, period, Instant.now()));

        buy(user, beforeRefund);
        notify(beforeRefund);

        assertThat(users.findById(user).orElseThrow().getProUntil()).isEqualTo(refunded);
        assertError(media(paidPack(), user), HttpStatus.PAYMENT_REQUIRED, "PACK_NOT_PURCHASED");
    }

    @Test
    void aRefundOfAnEarlierPeriodKeepsTheCurrentOne() throws Exception {
        UUID user = newUser();
        Instant firstStarted = Instant.now().minus(29, ChronoUnit.DAYS);
        Instant firstEnded = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant renewedUntil = firstEnded.plus(30, ChronoUnit.DAYS);
        String original = "sub-" + UUID.randomUUID();
        String first = "period-" + UUID.randomUUID();
        buy(user, transaction(PRO, user, firstEnded, null, original, first, firstStarted));
        notify(transaction(PRO, user, renewedUntil, null, original, "period-" + UUID.randomUUID(), firstEnded));

        notify(transaction(PRO, user, firstEnded, Instant.now(), original, first, firstStarted));

        assertThat(users.findById(user).orElseThrow().getProUntil()).isEqualTo(renewedUntil);
        assertThat(media(paidPack(), user)).hasStatusOk();
    }

    @Test
    void upgradingToYearlyKeepsProThroughTheYear() throws Exception {
        UUID user = newUser();
        Instant upgraded = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant yearEnds = upgraded.plus(365, ChronoUnit.DAYS);
        String original = "sub-" + UUID.randomUUID();
        String monthly = "period-" + UUID.randomUUID();
        Instant monthEnds = upgraded.plus(20, ChronoUnit.DAYS);
        buy(user, transaction(PRO, user, monthEnds, null, original, monthly, upgraded.minus(10, ChronoUnit.DAYS)));

        // Apple sends the new yearly period and revokes the monthly one as of the upgrade, in either order.
        notify(transaction("com.hypeman.pro.yearly", user, yearEnds, null, original, "period-" + UUID.randomUUID(), upgraded));
        notify(transaction(PRO, user, monthEnds, upgraded, original, monthly, upgraded.minus(10, ChronoUnit.DAYS)));

        assertThat(users.findById(user).orElseThrow().getProUntil()).isEqualTo(yearEnds);
    }

    @Test
    void aNotificationForSomeoneWeDontKnowIsAcknowledgedAndIgnored() throws Exception {
        assertThat(notify(transaction(PRO, UUID.randomUUID(), Instant.now().plus(1, ChronoUnit.DAYS), null))).hasStatusOk();
    }

    @Test
    void aForgedNotificationIsRefused() throws Exception {
        UUID user = newUser();
        Map<String, Object> claims = notificationClaims(transaction(PRO, user, Instant.now().plus(30, ChronoUnit.DAYS), null), "Sandbox");

        MvcTestResult result = mvc.post().uri("/v1/app-store/notifications").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("signedPayload", jws(claims)))).exchange();

        assertError(result, HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_TRANSACTION");
        assertThat(users.findById(user).orElseThrow().getProUntil()).isNull();
    }

    @Test
    void aPackIsSoldAsOneProductOnly() {
        Pack pack = paidPack();

        assertThatThrownBy(() -> packs.createDefaultPack("Copy " + word(), "Same product", false, pack.getProductId()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("already sold as");
        assertThatThrownBy(() -> packs.createDefaultPack("Odd " + word(), "Bad id", false, "not a product id"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("reverse-DNS");
    }

    private MvcTestResult buy(UUID user, String signedTransaction) throws Exception {
        return mvc.post().uri("/v1/purchases").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("signedTransaction", signedTransaction))).exchange();
    }

    private MvcTestResult notify(String signedTransaction) throws Exception {
        return mvc.post().uri("/v1/app-store/notifications").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("signedPayload", jws(notificationClaims(signedTransaction, "Xcode"))))).exchange();
    }

    private MvcTestResult media(Pack pack, UUID user) {
        return mvc.post().uri("/v1/packs/{id}/media", pack.getId()).header("Authorization", bearer(user)).exchange();
    }

    private String transaction(String productId, UUID account, Instant expires, Instant revoked) throws Exception {
        return transaction(productId, account, expires, revoked, "txn-" + UUID.randomUUID());
    }

    private String transaction(String productId, UUID account, Instant expires, Instant revoked, String originalId) throws Exception {
        return transaction(productId, account, expires, revoked, originalId, originalId, Instant.now());
    }

    private String transaction(String productId, UUID account, Instant expires, Instant revoked, String originalId,
                               String transactionId, Instant purchased) throws Exception {
        Map<String, Object> claims = transactionClaims(productId, account, expires, revoked, "Xcode");
        claims.put("originalTransactionId", originalId);
        claims.put("transactionId", transactionId);
        claims.put("purchaseDate", purchased.toEpochMilli());
        return jws(claims);
    }

    /** The fields of a StoreKit JWSTransaction that matter here. */
    private static Map<String, Object> transactionClaims(String productId, UUID account, Instant expires, Instant revoked, String environment) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("transactionId", "txn-" + UUID.randomUUID());
        claims.put("originalTransactionId", "txn-" + UUID.randomUUID());
        claims.put("bundleId", "com.hypeman.app");
        claims.put("productId", productId);
        claims.put("purchaseDate", Instant.now().toEpochMilli());
        claims.put("signedDate", Instant.now().toEpochMilli());
        claims.put("type", expires != null ? "Auto-Renewable Subscription" : "Non-Consumable");
        claims.put("appAccountToken", account.toString());
        claims.put("environment", environment);
        if (expires != null) {
            claims.put("expiresDate", expires.toEpochMilli());
        }
        if (revoked != null) {
            claims.put("revocationDate", revoked.toEpochMilli());
        }
        return claims;
    }

    private static Map<String, Object> notificationClaims(String signedTransaction, String environment) {
        return Map.of("notificationType", "DID_RENEW", "notificationUUID", UUID.randomUUID().toString(), "version", "2.0",
                "signedDate", Instant.now().toEpochMilli(),
                "data", Map.of("environment", environment, "bundleId", "com.hypeman.app", "signedTransactionInfo", signedTransaction));
    }

    /** Shaped like Apple's, but signed by nobody: Xcode's StoreKit testing works the same way. */
    private String jws(Map<String, Object> claims) throws Exception {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return base64.encodeToString("{\"alg\":\"ES256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(json.writeValueAsBytes(claims)) + "."
                + base64.encodeToString("not-a-signature".getBytes(StandardCharsets.UTF_8));
    }

    private UUID newUser() {
        return users.save(new User()).getId();
    }

    private String bearer(UUID user) {
        return "Bearer " + tokens.signIn(user, UUID.randomUUID()).accessToken();
    }

    private Pack paidPack() {
        String word = word();
        Pack pack = packs.createDefaultPack("Paid " + word, "Costs money", false, "com.hypeman.pack.test" + word);
        for (ClipRole role : ClipRole.values()) {
            UUID clipId = packs.requestClipUpload(pack.getId(), null, role, "Line for " + role, "video/mp4", 4_200_000).clipId();
            packs.completeClipUpload(clipId);
            await().atMost(Duration.ofSeconds(10)).until(() -> clips.findById(clipId).orElseThrow().getStatus() == ClipStatus.READY);
        }
        return packs.publish(pack.getId());
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
