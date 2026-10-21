package com.hypeman.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** Apple's Sign in with Apple REST API: exchanging an authorization code, and revoking the token it yields. */
@Component
class AppleClient {

    private final AuthProperties.AppleRevocation config;
    private final String clientId;
    private final RestClient http;
    private final Clock clock;

    AppleClient(AuthProperties properties, Clock clock) {
        this.config = properties.appleRevocation();
        this.clientId = properties.apple().clientIds().isEmpty() ? null : properties.apple().clientIds().getFirst();
        this.http = RestClient.create(config.baseUrl());
        this.clock = clock;
    }

    boolean isConfigured() {
        return StringUtils.hasText(config.teamId()) && StringUtils.hasText(config.keyId())
                && StringUtils.hasText(config.privateKey()) && clientId != null;
    }

    /** Swaps a single-use authorization code for Apple's tokens. Throws RestClientException on a 4xx or 5xx. */
    TokenResponse exchange(String authorizationCode) {
        MultiValueMap<String, String> form = form();
        form.add("code", authorizationCode);
        form.add("grant_type", "authorization_code");
        return http.post().uri("/auth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(TokenResponse.class);
    }

    /** Ends the user's Sign in with Apple for this app. Throws RestClientException on a 4xx or 5xx. */
    void revoke(String refreshToken) {
        MultiValueMap<String, String> form = form();
        form.add("token", refreshToken);
        form.add("token_type_hint", "refresh_token");
        http.post().uri("/auth/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().toBodilessEntity();
    }

    private MultiValueMap<String, String> form() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret());
        return form;
    }

    /** Apple's client secret: a short-lived JWT signed with our Sign in with Apple key. */
    private String clientSecret() {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(config.teamId())
                .subject(clientId)
                .audience("https://appleid.apple.com")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(config.keyId()).build(), claims);
        try {
            jwt.sign(new ECDSASigner(privateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Couldn't sign Apple's client secret", e);
        }
        return jwt.serialize();
    }

    private ECPrivateKey privateKey() {
        String base64 = config.privateKey().replace("\\n", "\n")
                .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("HYPEMAN_AUTH_APPLE_PRIVATE_KEY isn't a .p8 Sign in with Apple key", e);
        }
    }

    record TokenResponse(@JsonProperty("refresh_token") String refreshToken, @JsonProperty("id_token") String idToken) {
    }
}
