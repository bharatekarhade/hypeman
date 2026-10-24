package com.hypeman.purchase.service;

import com.hypeman.common.ApplicationException;
import com.hypeman.common.Ids;
import com.hypeman.pack.model.Pack;
import com.hypeman.pack.repository.PackRepository;
import com.hypeman.purchase.config.AppStoreProperties;
import com.hypeman.purchase.model.Entitlements;
import com.hypeman.purchase.model.PackPurchase;
import com.hypeman.purchase.model.ProTransaction;
import com.hypeman.purchase.model.VerifiedTransaction;
import com.hypeman.purchase.repository.PackPurchaseRepository;
import com.hypeman.purchase.repository.ProTransactionRepository;
import com.hypeman.user.model.User;
import com.hypeman.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pro and single packs, from Apple's signed transactions. Pro doesn't include packs: every pack is bought on its own, so
 * each sale is its creator's. When someone is signed in, the app attaches their user id to the purchase as its
 * appAccountToken, which is how both its own reports and Apple's notifications find them. A purchase made signed out
 * carries none; it still unlocks its pack on that device, and joins the account that first reports it after signing in. The same transaction can
 * arrive any number of times, from the app and from Apple, in any order, and a copy signed before a refund stays valid,
 * so applying one never undoes a refund or a later purchase.
 */
@Service
public class PurchaseService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseService.class);

    private final TransactionVerifier verifier;
    private final AppStoreProperties properties;
    private final UserRepository users;
    private final PackRepository packs;
    private final PackPurchaseRepository purchases;
    private final ProTransactionRepository proTransactions;
    private final Clock clock;

    PurchaseService(TransactionVerifier verifier, AppStoreProperties properties, UserRepository users, PackRepository packs,
                    PackPurchaseRepository purchases, ProTransactionRepository proTransactions, Clock clock) {
        this.verifier = verifier;
        this.properties = properties;
        this.users = users;
        this.packs = packs;
        this.purchases = purchases;
        this.proTransactions = proTransactions;
        this.clock = clock;
    }

    /** A purchase or restore the app reports: one the user made signed in, or one made signed out that nobody has claimed. */
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

    /** Renewals, expiries, refunds and revocations from App Store Server Notifications. */
    @Transactional
    public void handleNotification(String signedPayload) {
        Optional<VerifiedTransaction> notified = verifier.verifyNotification(signedPayload);
        if (notified.isEmpty()) {
            return;
        }
        VerifiedTransaction transaction = notified.get();
        UUID userId = transaction.appAccountToken() != null ? transaction.appAccountToken() : ownerOf(transaction.originalTransactionId());
        if (userId == null || !users.existsById(userId)) {
            // Bought signed out and not claimed yet, outside the app (an offer code, say), or by an account that's been
            // purged. The app reports it once the buyer signs in.
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
        User user = users.findById(userId).orElseThrow();
        return new Entitlements(user.getProUntil(),
                purchases.findByUserIdAndRevokedAtIsNull(userId).stream().map(PackPurchase::getPackId).toList());
    }

    /**
     * Whether a paid pack may be downloaded: the signed-in user bought it, or the app proves a purchase of it with Apple's
     * signed transaction, which is how a pack bought signed out downloads. A refund recorded for that purchase still counts.
     *
     * @param userId null when nobody is signed in
     * @param signedTransaction StoreKit's jwsRepresentation of the pack's purchase; null if the app sent none
     */
    @Transactional(readOnly = true)
    public boolean owns(UUID userId, Pack pack, String signedTransaction) {
        if (userId != null && purchases.existsByUserIdAndPackIdAndRevokedAtIsNull(userId, pack.getId())) {
            return true;
        }
        if (signedTransaction == null) {
            return false;
        }
        VerifiedTransaction transaction = verifier.verifyTransaction(signedTransaction);
        return transaction.productId().equals(pack.getProductId()) && transaction.revokedAt() == null
                && purchases.findByOriginalTransactionId(transaction.originalTransactionId()).map(PackPurchase::isActive).orElse(true);
    }

    /** Who claimed a purchase made signed out, if anyone has. */
    private UUID ownerOf(String originalTransactionId) {
        return purchases.findByOriginalTransactionId(originalTransactionId).map(PackPurchase::getUserId)
                .or(() -> proTransactions.findFirstByOriginalTransactionId(originalTransactionId).map(ProTransaction::getUserId))
                .orElse(null);
    }

    /** False when the product is neither Pro nor a pack's. */
    private boolean apply(UUID userId, VerifiedTransaction transaction) {
        if (properties.proProductIds().contains(transaction.productId())) {
            applyPro(userId, transaction);
            return true;
        }
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

    /** Records the period, then sets Pro to end with the latest one still standing. */
    private void applyPro(UUID userId, VerifiedTransaction transaction) {
        if (transaction.expiresAt() == null) {
            throw new ApplicationException("INVALID_TRANSACTION", "We couldn't confirm that purchase with Apple. Please try again.");
        }
        // Locked first, so two periods arriving together can't each work out Pro without the other.
        User user = users.findLockedById(userId).orElseThrow();
        ProTransaction period = proTransactions.findByTransactionId(transaction.transactionId())
                .orElseGet(() -> new ProTransaction(Ids.uuidV7(clock), userId, transaction.transactionId()));
        UUID owner = Optional.ofNullable(ownerOf(transaction.originalTransactionId())).orElse(period.getUserId());
        if (!period.getUserId().equals(userId) || !owner.equals(userId)) {
            throw new ApplicationException("PURCHASE_ACCOUNT_MISMATCH",
                    "This subscription already belongs to a different Hypeman account. Sign in with that account to use it.");
        }
        period.apply(transaction);
        proTransactions.saveAndFlush(period);
        Instant proUntil = proTransactions.findByUserId(userId).stream()
                .map(ProTransaction::getAccessUntil)
                .max(Comparator.naturalOrder())
                .orElse(null);
        user.updateProUntil(proUntil);
    }
}
