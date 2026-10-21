package com.hypeman.auth;

import com.hypeman.common.ApiException;
import com.hypeman.user.IdentityProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Checks an Apple or Google ID token's signature, issuer, audience, expiry and, for Apple, nonce. */
public class IdentityTokenVerifier {

    private final Map<IdentityProvider, JwtDecoder> decoders;

    IdentityTokenVerifier(Map<IdentityProvider, JwtDecoder> decoders) {
        this.decoders = decoders;
    }

    /** Fetches each provider's signing keys from its published key set, and caches them. */
    static IdentityTokenVerifier forProviders(AuthProperties properties) {
        return new IdentityTokenVerifier(Map.of(
                IdentityProvider.APPLE, decoder(properties.apple(), NimbusJwtDecoder.withJwkSetUri(properties.apple().jwkSetUri()).build()),
                IdentityProvider.GOOGLE, decoder(properties.google(), NimbusJwtDecoder.withJwkSetUri(properties.google().jwkSetUri()).build())));
    }

    /** Adds the provider's issuer and audience checks to a decoder that already checks signatures. */
    static JwtDecoder decoder(AuthProperties.Provider provider, NimbusJwtDecoder signatureChecking) {
        signatureChecking.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                claim("iss", jwt -> provider.issuers().contains(jwt.getClaimAsString("iss"))),
                claim("aud", jwt -> jwt.getAudience() != null && jwt.getAudience().stream().anyMatch(provider.clientIds()::contains))));
        return signatureChecking;
    }

    /**
     * @param rawNonce for Apple, the nonce the app generated; Apple puts its SHA-256 in the token, which proves the
     *     token was minted for this sign-in and not replayed from another
     */
    public VerifiedIdentity verify(IdentityProvider provider, String token, String rawNonce) {
        Jwt jwt;
        try {
            jwt = decoders.get(provider).decode(token);
        } catch (JwtException e) {
            throw invalid();
        }
        if (provider == IdentityProvider.APPLE && !sha256Hex(rawNonce).equals(jwt.getClaimAsString("nonce"))) {
            throw invalid();
        }
        return new VerifiedIdentity(provider, jwt.getSubject(), jwt.getClaimAsString("email"));
    }

    /**
     * An ID token Apple's token endpoint returned to us directly over TLS. It wasn't handed over by the app, so there is
     * no replay to guard against and no nonce to check.
     */
    VerifiedIdentity verifyFromApple(String idToken) {
        try {
            Jwt jwt = decoders.get(IdentityProvider.APPLE).decode(idToken);
            return new VerifiedIdentity(IdentityProvider.APPLE, jwt.getSubject(), jwt.getClaimAsString("email"));
        } catch (JwtException e) {
            throw invalid();
        }
    }

    private static OAuth2TokenValidator<Jwt> claim(String name, Predicate<Jwt> check) {
        OAuth2Error error = new OAuth2Error("invalid_token", "The " + name + " claim isn't accepted", null);
        return jwt -> check.test(jwt) ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(List.of(error));
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_IDENTITY_TOKEN", "The sign-in token isn't valid. Please sign in again.");
    }

    public record VerifiedIdentity(IdentityProvider provider, String subject, String email) {
    }
}
