package com.hypeman.user.model;

import java.util.UUID;

/**
 * Published inside the deleting transaction, so other modules can drop what they hold for the user (devices and
 * tokens today) without this module depending on them.
 */
public record UserDeletedEvent(UUID userId) {
}
