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
 * Pro and single packs, from Apple's signed transactions. The app attaches the buyer's user id to every purchase as its
 * appAccountToken, which is how both its own reports and Apple's notifications find the user. The same transaction can
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

    /** A purchase or restore the app reports. It must be the signed-in user's: the app attached their id when buying. */
    @Transactional
    public Entitlements record(UUID userId, String signedTransaction) {
        VerifiedTransaction transaction = verifier.verifyTransaction(signedTransaction);
        if (!userId.equals(transaction.appAccountToken())) {
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
        UUID userId = transaction.appAccountToken();
        if (userId == null || !users.existsById(userId)) {
            // Bought outside the app (an offer code, say) or by an account that's been purged. The app reports it on its
            // next restore if the buyer is signed in then.
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

    /** Pro covers every pack; otherwise the user must have bought this one and kept it. */
    @Transactional(readOnly = true)
    public boolean owns(UUID userId, UUID packId) {
        if (userId == null) {
            return false;
        }
        boolean pro = users.findById(userId).map(user -> user.isProAt(clock.instant())).orElse(false);
        return pro || purchases.existsByUserIdAndPackIdAndRevokedAtIsNull(userId, packId);
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
        if (!period.getUserId().equals(userId)) {
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
