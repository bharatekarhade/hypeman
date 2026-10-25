package com.hypeman.yell.model;

import java.util.List;
import java.util.UUID;

/**
 * A yell went out to new recipients.
 *
 * @param firstSend whether this sent the yell at all, so its files still need checking
 */
public record YellSentEvent(UUID yellId, List<UUID> recipientIds, boolean firstSend) {
}
