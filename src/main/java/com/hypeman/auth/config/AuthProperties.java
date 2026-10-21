package com.hypeman.auth.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param issuer the iss claim of our own access tokens
 * @param signingKey PEM-encoded PKCS#8 RSA private key for access tokens, from HYPEMAN_AUTH_SIGNING_KEY
 * @param allowEphemeralSigningKey when no key is set, make a throwaway one; local development only, since every
 *     restart then signs everyone out
 */
@ConfigurationProperties("hypeman.auth")
public record AuthProperties(
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        String signingKey,
        boolean allowEphemeralSigningKey,
        Provider apple,
        Provider google,
        AppleRevocation appleRevocation) {

    /**
     * @param jwkSetUri where the provider publishes the keys its ID tokens are signed with
     * @param issuers accepted iss claims
     * @param clientIds accepted aud claims: our bundle id for Apple, our iOS OAuth client id for Google
     */
    public record Provider(String jwkSetUri, List<String> issuers, List<String> clientIds) {
    }

    /**
     * Apple's REST API, for revoking a user's sign-in when they delete their account. The key is a Sign in with Apple
     * key from the developer account; the client id is the first of {@code apple.client-ids}.
     *
     * @param baseUrl https://appleid.apple.com
     * @param privateKey the .p8 file's contents
     * @param skipWhenUnconfigured without a key, delete accounts without revoking; local development only
     */
    public record AppleRevocation(String baseUrl, String teamId, String keyId, String privateKey, boolean skipWhenUnconfigured) {
    }
}
