package com.hypeman.auth;

import com.hypeman.auth.IdentityTokenVerifier.VerifiedIdentity;
import com.hypeman.auth.TokenService.TokenPair;
import com.hypeman.user.IdentityProvider;
import com.hypeman.user.User;
import com.hypeman.user.UserService;
import com.hypeman.user.UserView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
class AuthController {

    private final IdentityTokenVerifier verifier;
    private final UserService users;
    private final TokenService tokens;

    AuthController(IdentityTokenVerifier verifier, UserService users, TokenService tokens) {
        this.verifier = verifier;
        this.users = users;
        this.tokens = tokens;
    }

    @PostMapping("/apple")
    SignInResponse apple(@Valid @RequestBody AppleSignIn body) {
        return signIn(verifier.verify(IdentityProvider.APPLE, body.identityToken(), body.nonce()), body.deviceId());
    }

    @PostMapping("/google")
    SignInResponse google(@Valid @RequestBody GoogleSignIn body) {
        return signIn(verifier.verify(IdentityProvider.GOOGLE, body.idToken(), null), body.deviceId());
    }

    @PostMapping("/refresh")
    TokenPair refresh(@Valid @RequestBody RefreshRequest body) {
        return tokens.refresh(body.refreshToken());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt token, @Valid @RequestBody RefreshRequest body) {
        tokens.logout(TokenService.userId(token), body.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private SignInResponse signIn(VerifiedIdentity identity, UUID deviceId) {
        User user = users.findOrCreate(identity.provider(), identity.subject(), identity.email());
        TokenPair pair = tokens.signIn(user.getId(), deviceId);
        return new SignInResponse(pair.accessToken(), pair.accessTokenExpiresAt(), pair.refreshToken(), UserView.of(user));
    }

    /** @param nonce the raw nonce the app generated; it gave Apple its SHA-256 */
    record AppleSignIn(@NotBlank String identityToken, @NotBlank String nonce, @NotNull UUID deviceId) {
    }

    record GoogleSignIn(@NotBlank String idToken, @NotNull UUID deviceId) {
    }

    record RefreshRequest(@NotBlank String refreshToken) {
    }

    record SignInResponse(String accessToken, Instant accessTokenExpiresAt, String refreshToken, UserView user) {
    }
}
