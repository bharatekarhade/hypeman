package com.hypeman.purchase.service;

import com.hypeman.common.ApplicationException;
import com.hypeman.common.Ids;
import com.hypeman.pack.model.Pack;
import com.hypeman.pack.repository.PackRepository;
import com.hypeman.purchase.config.AppStoreProperties;
import com.hypeman.purchase.model.Entitlements;
import com.hypeman.purchase.model.PackPurchase;
import com.hypeman.purchase.model.VerifiedTransaction;
import com.hypeman.purchase.repository.PackPurchaseRepository;
import com.hypeman.user.repository.UserRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single packs, from Apple's signed transactions: every pack is bought on its own, so each sale is its creator's. The app
 * attaches the user's id to the purchase as its appAccountToken, which is how both its own reports and Apple's
 * notifications find them. A purchase that carries none (an offer code redeemed outside the app, say) joins the account
 * that first reports it. The same transaction can arrive any number of times, from the app and from Apple, in any order,
 * and a copy signed before a refund stays valid, so applying one never undoes a refund or a later purchase.
 */
@Service
public class PurchaseService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseService.class);

    private final TransactionVerifier verifier;
    private final UserRepository users;
    private final PackRepository packs;
    private final PackPurchaseRepository purchases;
    private final Clock clock;

    PurchaseService(TransactionVerifier verifier, UserRepository users, PackRepository packs, PackPurchaseRepository purchases,
                    Clock clock) {
        this.verifier = verifier;
        this.users = users;
        this.packs = packs;
        this.purchases = purchases;
        this.clock = clock;
    }

    /** A purchase or restore the app reports: one the user made, or one without an account that nobody has claimed. */
    @Transactional
    public Entitlements record(UUID userId, String signedTransaction) {
        VerifiedTransaction transaction = verifier.verifyTransaction(signedTransaction);
        if (transaction.appAccountToken() != null && !userId.equals(transaction.appAccountToken())) {
            throw new ApplicationException("PURCHASE_ACCOUNT_MISMATCH",
                    "This purchase was made while signed in to a different Hypeman account. Sign in with that account to use it.");
        }
        if (!apply(userId, transaction)) {
            throw new ApplicationException("UNKNOWN_PRODUCT", "That isn't something Hypeman sells.");
        }
        return entitlements(userId);
    }

    /** Refunds and revocations from App Store Server Notifications. */
    @Transactional
    public void handleNotification(String signedPayload) {
        Optional<VerifiedTransaction> notified = verifier.verifyNotification(signedPayload);
        if (notified.isEmpty()) {
            return;
        }
        VerifiedTransaction transaction = notified.get();
        UUID userId = transaction.appAccountToken() != null ? transaction.appAccountToken() : ownerOf(transaction.originalTransactionId());
        if (userId == null || !users.existsById(userId)) {
            // Not claimed yet, bought outside the app (an offer code, say), or by an account that's been purged. The app
            // reports it once the buyer signs in.
            log.info("Ignoring a notification for transaction {} with no known user", transaction.originalTransactionId());
            return;
        }
        try {
            if (!apply(userId, transaction)) {
                log.warn("Ignoring a notification for unknown product {}", transaction.productId());
            }
        } catch (ApplicationException e) {
            // Answering with an error would only make Apple send it again.
            log.warn("Ignoring a notification for transaction {}: {}", transaction.originalTransactionId(), e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Entitlements entitlements(UUID userId) {
        return new Entitlements(purchases.findByUserIdAndRevokedAtIsNull(userId).stream().map(PackPurchase::getPackId).toList());
    }

    /**
     * Whether the user may download a paid pack: they bought it, or the app proves their purchase of it with Apple's signed
     * transaction before the server has heard of it. That transaction must not belong to another account, and a refund
     * recorded for it still counts.
     *
     * @param signedTransaction StoreKit's jwsRepresentation of the pack's purchase; null if the app sent none
     */
    @Transactional(readOnly = true)
    public boolean owns(UUID userId, Pack pack, String signedTransaction) {
        if (purchases.existsByUserIdAndPackIdAndRevokedAtIsNull(userId, pack.getId())) {
            return true;
        }
        if (signedTransaction == null) {
            return false;
        }
        VerifiedTransaction transaction = verifier.verifyTransaction(signedTransaction);
        if (transaction.appAccountToken() != null && !userId.equals(transaction.appAccountToken())) {
            return false;
        }
        return transaction.productId().equals(pack.getProductId()) && transaction.revokedAt() == null
                && purchases.findByOriginalTransactionId(transaction.originalTransactionId())
                        .map(purchase -> purchase.isActive() && purchase.getUserId().equals(userId))
                        .orElse(true);
    }

    /** Who claimed a purchase made without an account, if anyone has. */
    private UUID ownerOf(String originalTransactionId) {
        return purchases.findByOriginalTransactionId(originalTransactionId).map(PackPurchase::getUserId).orElse(null);
    }

    /** False when the product isn't a pack's. */
    private boolean apply(UUID userId, VerifiedTransaction transaction) {
        Optional<Pack> pack = packs.findByProductId(transaction.productId());
        if (pack.isEmpty()) {
            return false;
        }
        UUID packId = pack.get().getId();
        PackPurchase purchase = purchases.findByOriginalTransactionId(transaction.originalTransactionId())
                .or(() -> purchases.findByUserIdAndPackId(userId, packId))
                .orElseGet(() -> new PackPurchase(Ids.uuidV7(clock), userId, packId));
        if (!purchase.getUserId().equals(userId)) {
            // One Apple purchase unlocks the pack for one Hypeman account.
            throw new ApplicationException("PURCHASE_ACCOUNT_MISMATCH",
                    "This purchase already belongs to a different Hypeman account. Sign in with that account to use it.");
        }
        purchase.apply(transaction);
        purchases.save(purchase);
        return true;
    }
}
