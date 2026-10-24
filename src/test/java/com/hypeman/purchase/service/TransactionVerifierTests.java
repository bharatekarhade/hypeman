package com.hypeman.purchase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hypeman.common.ApplicationException;
import com.hypeman.purchase.config.AppStoreProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Which environments are trusted. Xcode's transactions aren't signed, so outside local development they mean nothing. */
class TransactionVerifierTests {

    private static final String XCODE_PACK = jws("""
            {"transactionId": "1", "originalTransactionId": "1", "bundleId": "com.hypeman.app", "productId": "com.hypeman.pack.coach",
             "purchaseDate": 1800000000000, "signedDate": 1800000000000, "type": "Non-Consumable", "environment": "Xcode",
             "appAccountToken": "0192d1a4-0000-7000-8000-000000000001"}""");

    @Test
    void xcodeTransactionsAreRefusedUnlessTurnedOn() {
        assertThatThrownBy(() -> verifier(false, 123L).verifyTransaction(XCODE_PACK))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("couldn't confirm");
    }

    @Test
    void xcodeTransactionsAreReadWhenTurnedOn() {
        assertThat(verifier(true, null).verifyTransaction(XCODE_PACK).productId()).isEqualTo("com.hypeman.pack.coach");
    }

    @Test
    void anotherAppsTransactionIsRefused() {
        String otherApp = jws("""
                {"transactionId": "1", "originalTransactionId": "1", "bundleId": "com.someone.else", "productId": "x.y",
                 "purchaseDate": 1800000000000, "signedDate": 1800000000000, "environment": "Xcode"}""");

        assertThatThrownBy(() -> verifier(true, null).verifyTransaction(otherApp)).isInstanceOf(ApplicationException.class);
    }

    @Test
    void productionIsRefusedUntilTheAppAppleIdIsSet() {
        String production = jws("""
                {"transactionId": "1", "originalTransactionId": "1", "bundleId": "com.hypeman.app", "productId": "x.y",
                 "purchaseDate": 1800000000000, "signedDate": 1800000000000, "environment": "Production"}""");

        assertThatThrownBy(() -> verifier(false, null).verifyTransaction(production)).isInstanceOf(ApplicationException.class);
    }

    private static TransactionVerifier verifier(boolean acceptXcode, Long appAppleId) {
        return new TransactionVerifier(new AppStoreProperties("com.hypeman.app", appAppleId, List.of(), acceptXcode),
                JsonMapper.builder().build());
    }

    private static String jws(String payload) {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return base64.encodeToString("{\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + base64.encodeToString(new byte[] {1});
    }
}
