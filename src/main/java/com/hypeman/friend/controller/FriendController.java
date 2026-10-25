package com.hypeman.friend.controller;

import com.hypeman.auth.service.TokenService;
import com.hypeman.friend.service.FriendshipService;
import com.hypeman.friend.service.InviteService;
import com.hypeman.friend.service.InviteService.Invite;
import com.hypeman.user.model.UserView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Invites, friends and blocks. All need a signed-in user. */
@RestController
class FriendController {

    private final InviteService invites;
    private final FriendshipService friendships;

    FriendController(InviteService invites, FriendshipService friendships) {
        this.invites = invites;
        this.friendships = friendships;
    }

    @PostMapping("/v1/invites")
    Invite invite(@AuthenticationPrincipal Jwt token) {
        return invites.create(TokenService.userId(token));
    }

    @PostMapping("/v1/invites/{code}/accept")
    FriendResponse accept(@AuthenticationPrincipal Jwt token, @PathVariable String code) {
        return new FriendResponse(invites.accept(TokenService.userId(token), code));
    }

    @GetMapping("/v1/friends")
    FriendsResponse friends(@AuthenticationPrincipal Jwt token) {
        return new FriendsResponse(friendships.friends(TokenService.userId(token)));
    }

    @DeleteMapping("/v1/friends/{userId}")
    ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt token, @PathVariable UUID userId) {
        friendships.remove(TokenService.userId(token), userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/blocks")
    ResponseEntity<Void> block(@AuthenticationPrincipal Jwt token, @Valid @RequestBody BlockRequest body) {
        friendships.block(TokenService.userId(token), body.userId());
        return ResponseEntity.noContent().build();
    }

    record FriendResponse(UserView friend) {
    }

    record FriendsResponse(List<UserView> friends) {
    }

    record BlockRequest(@NotNull UUID userId) {
    }
}
