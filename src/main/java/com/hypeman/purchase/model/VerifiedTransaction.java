package com.hypeman.purchase.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A StoreKit transaction whose signature checked out.
 *
 * @param transactionId Apple's id for this transaction; each subscription period has its own
 * @param originalTransactionId the id of the first purchase, shared by a subscription's renewals
 * @param appAccountToken the user id the app attached when buying; null if bought outside the app, such as with an offer
 *     code
 * @param expiresAt when a subscription period ends; null for a pack
 * @param revokedAt when Apple refunded or revoked it, if it did
 * @param environment PRODUCTION, SANDBOX or XCODE
 */
public record VerifiedTransaction(String transactionId, String originalTransactionId, String productId, UUID appAccountToken,
                                  Instant purchasedAt, Instant expiresAt, Instant revokedAt, String environment) {
}
