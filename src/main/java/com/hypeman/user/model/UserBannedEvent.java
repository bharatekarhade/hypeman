package com.hypeman.user.model;

import java.util.UUID;

/**
 * Published inside the banning transaction, so other modules can act on it without this module depending on them: the
 * user's sessions end.
 */
public record UserBannedEvent(UUID userId) {
}
