package com.hypeman.user;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/me")
class MeController {

    private final UserService users;

    MeController(UserService users) {
        this.users = users;
    }

    @GetMapping
    MeResponse me(@AuthenticationPrincipal Jwt token) {
        UUID userId = UUID.fromString(token.getSubject());
        User user = users.get(userId);
        List<IdentityView> identities = users.identities(userId).stream()
                .map(identity -> new IdentityView(identity.getProvider(), identity.getEmail()))
                .toList();
        return new MeResponse(UserView.of(user), identities, user.getProUntil());
    }

    /**
     * 202: the account is gone for the user now; the purge job removes the rest within 30 days. A user who signs in with
     * Apple confirms with Sign in with Apple first, and the app sends that fresh authorization code so the server can
     * revoke the sign-in.
     */
    @DeleteMapping
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt token, @RequestBody(required = false) DeleteRequest body) {
        users.delete(UUID.fromString(token.getSubject()), body != null ? body.appleAuthorizationCode() : null);
        return ResponseEntity.accepted().build();
    }

    record DeleteRequest(String appleAuthorizationCode) {
    }

    record MeResponse(UserView user, List<IdentityView> identities, Instant proUntil) {
    }

    record IdentityView(IdentityProvider provider, String email) {
    }
}
