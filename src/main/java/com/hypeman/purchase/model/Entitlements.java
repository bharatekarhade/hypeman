package com.hypeman.purchase.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a user has paid for.
 *
 * @param proUntil when their Pro subscription runs out; null if they never had it
 * @param packIds the packs they bought on their own, refunds left out
 */
public record Entitlements(Instant proUntil, List<UUID> packIds) {
}
