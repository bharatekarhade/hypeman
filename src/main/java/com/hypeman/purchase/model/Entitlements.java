package com.hypeman.purchase.model;

import java.util.List;
import java.util.UUID;

/**
 * What a user has paid for.
 *
 * @param packIds the packs they bought on their own, refunds left out
 */
public record Entitlements(List<UUID> packIds) {
}
