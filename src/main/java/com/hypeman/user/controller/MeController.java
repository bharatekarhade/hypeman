package com.hypeman.user.controller;

import com.hypeman.user.model.IdentityProvider;
import com.hypeman.user.model.User;
import com.hypeman.user.model.UserView;
import com.hypeman.user.service.UserService;
import com.hypeman.yell.service.YellService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/me")
class MeController {

    private final UserService users;
    private final YellService yells;

    MeController(UserService users, YellService yells) {
        this.users = users;
        this.yells = yells;
    }

    @GetMapping
    MeResponse me(@AuthenticationPrincipal Jwt token) {
        UUID userId = UUID.fromString(token.getSubject());
        User user = users.get(userId);
        List<IdentityView> identities = users.identities(userId).stream()
                .map(identity -> new IdentityView(identity.getProvider(), identity.getEmail()))
                .toList();
        return new MeResponse(UserView.of(user), identities, user.getProUntil(), yells.sendsLeft(userId));
    }

    /** The name friends see. */
    @PatchMapping
    UserView update(@AuthenticationPrincipal Jwt token, @Valid @RequestBody UpdateRequest body) {
        return UserView.of(users.rename(UUID.fromString(token.getSubject()), body.displayName()));
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

    /** @param sendsLeftThisMonth free friend yells left this month; null for Pro, which has no cap */
    record MeResponse(UserView user, List<IdentityView> identities, Instant proUntil, Integer sendsLeftThisMonth) {
    }

    record UpdateRequest(@NotBlank @Size(max = 40) String displayName) {
    }

    record IdentityView(IdentityProvider provider, String email) {
    }
}
