package com.hypeman.purchase.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A StoreKit transaction whose signature checked out. Hypeman sells only packs, each bought once, so there's no expiry.
 *
 * @param transactionId Apple's id for this transaction
 * @param originalTransactionId the id of the first purchase, the same across restores and devices
 * @param appAccountToken the user id the app attached when buying; null if bought outside the app, such as with an offer
 *     code
 * @param revokedAt when Apple refunded or revoked it, if it did
 * @param environment PRODUCTION, SANDBOX or XCODE
 */
public record VerifiedTransaction(String transactionId, String originalTransactionId, String productId, UUID appAccountToken,
                                  Instant purchasedAt, Instant revokedAt, String environment) {
}
