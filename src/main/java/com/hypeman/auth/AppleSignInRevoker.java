package com.hypeman.auth;

import com.hypeman.common.ApiException;
import com.hypeman.user.IdentityProvider;
import com.hypeman.user.IdentityRevoker;
import com.hypeman.user.UserIdentity;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Revokes Sign in with Apple when an account is deleted. The app gets a fresh authorization code by asking the user to
 * confirm with Apple; we exchange it, check it is this user's Apple account, and revoke the resulting token. Nothing
 * from Apple is stored. Google sign-in is revoked by the app itself, with the Google Sign-In SDK's disconnect.
 */
@Component
class AppleSignInRevoker implements IdentityRevoker {

    private static final Logger log = LoggerFactory.getLogger(AppleSignInRevoker.class);

    private final AppleClient apple;
    private final IdentityTokenVerifier verifier;
    private final AuthProperties properties;

    AppleSignInRevoker(AppleClient apple, IdentityTokenVerifier verifier, AuthProperties properties) {
        this.apple = apple;
        this.verifier = verifier;
        this.properties = properties;
    }

    @Override
    public void revoke(List<UserIdentity> identities, String appleAuthorizationCode) {
        Optional<UserIdentity> appleIdentity = identities.stream()
                .filter(identity -> identity.getProvider() == IdentityProvider.APPLE)
                .findFirst();
        if (appleIdentity.isEmpty()) {
            return;
        }
        if (!apple.isConfigured()) {
            if (properties.appleRevocation().skipWhenUnconfigured()) {
                log.warn("No Sign in with Apple key set; deleting the account without revoking its Apple sign-in.");
                return;
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "APPLE_REVOKE_UNAVAILABLE", "Account deletion is unavailable right now. Please try again later.");
        }
        if (!StringUtils.hasText(appleAuthorizationCode)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "APPLE_REAUTH_REQUIRED", "Confirm with Apple to delete your account.");
        }

        AppleClient.TokenResponse tokens;
        try {
            tokens = apple.exchange(appleAuthorizationCode);
        } catch (HttpClientErrorException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_APPLE_AUTHORIZATION_CODE", "Apple didn't accept the confirmation. Please try again.");
        } catch (RestClientException e) {
            throw appleUnreachable(e);
        }
        if (!verifier.verifyFromApple(tokens.idToken()).subject().equals(appleIdentity.get().getSubject())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "APPLE_ACCOUNT_MISMATCH", "Confirm with the Apple account you signed in with.");
        }
        try {
            apple.revoke(tokens.refreshToken());
        } catch (RestClientException e) {
            throw appleUnreachable(e);
        }
    }

    private static ApiException appleUnreachable(RestClientException e) {
        log.error("Sign in with Apple revocation failed", e);
        return new ApiException(HttpStatus.BAD_GATEWAY, "APPLE_REVOKE_FAILED", "Couldn't reach Apple to finish deleting your account. Please try again.");
    }
}
