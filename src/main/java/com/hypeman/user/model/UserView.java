package com.hypeman.user.model;

import java.util.UUID;

/** The user as the app sees it, in sign-in and /v1/me responses. */
public record UserView(UUID id, String displayName) {

    public static UserView of(User user) {
        return new UserView(user.getId(), user.getDisplayName());
    }
}
