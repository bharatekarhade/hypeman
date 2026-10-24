package com.hypeman.purchase.service;

import com.apple.itunes.storekit.model.Data;
import com.apple.itunes.storekit.model.Environment;
import com.apple.itunes.storekit.model.JWSTransactionDecodedPayload;
import com.apple.itunes.storekit.model.ResponseBodyV2DecodedPayload;
import com.apple.itunes.storekit.verification.SignedDataVerifier;
import com.apple.itunes.storekit.verification.VerificationException;
import com.hypeman.common.ApplicationException;
import com.hypeman.purchase.config.AppStoreProperties;
import com.hypeman.purchase.model.VerifiedTransaction;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks that StoreKit transactions and App Store Server Notifications were signed by Apple for this app, using Apple's
 * library and its root certificates (src/main/resources/apple). Each environment has its own verifier, picked by what
 * the payload claims and then checked against it: Sandbox always, Production once the app's Apple ID is configured, and
 * Xcode only when local development turns it on, since Xcode's transactions carry no signature at all.
 */
@Component
public class TransactionVerifier {

    private static final Logger log = LoggerFactory.getLogger(TransactionVerifier.class);
    private static final List<String> ROOT_CERTIFICATES = List.of("apple/AppleRootCA-G3.cer", "apple/AppleRootCA-G2.cer");

    private final Map<Environment, SignedDataVerifier> verifiers = new EnumMap<>(Environment.class);
    private final JsonMapper json;

    TransactionVerifier(AppStoreProperties properties, JsonMapper json) {
        this.json = json;
        if (!StringUtils.hasText(properties.bundleId())) {
            log.warn("hypeman.app-store.bundle-id isn't set, so every purchase will be rejected");
            return;
        }
        List<byte[]> roots = ROOT_CERTIFICATES.stream().map(TransactionVerifier::read).toList();
        verifiers.put(Environment.SANDBOX, verifier(roots, properties, Environment.SANDBOX));
        if (properties.appAppleId() != null) {
            verifiers.put(Environment.PRODUCTION, verifier(roots, properties, Environment.PRODUCTION));
        } else {
            log.warn("hypeman.app-store.app-apple-id isn't set, so App Store purchases will be rejected; Sandbox still works");
        }
        if (properties.acceptXcodeTransactions()) {
            log.warn("Accepting unsigned Xcode StoreKit transactions. Never turn this on outside local development");
            verifiers.put(Environment.XCODE, verifier(roots, properties, Environment.XCODE));
        }
    }

    /** A transaction the app sends after a purchase or a restore. */
    public VerifiedTransaction verifyTransaction(String signedTransaction) {
        Environment environment = claimedEnvironment(signedTransaction, "environment");
        try {
            return toVerified(verifierFor(environment).verifyAndDecodeTransaction(signedTransaction));
        } catch (VerificationException e) {
            throw invalid(e);
        }
    }

    /** The transaction a notification is about; empty for notifications that aren't about one, such as a TEST. */
    public Optional<VerifiedTransaction> verifyNotification(String signedPayload) {
        Environment environment = claimedEnvironment(signedPayload, "data", "environment");
        SignedDataVerifier verifier = verifierFor(environment);
        try {
            ResponseBodyV2DecodedPayload notification = verifier.verifyAndDecodeNotification(signedPayload);
            Data data = notification.getData();
            if (data == null || data.getSignedTransactionInfo() == null) {
                return Optional.empty();
            }
            return Optional.of(toVerified(verifier.verifyAndDecodeTransaction(data.getSignedTransactionInfo())));
        } catch (VerificationException e) {
            throw invalid(e);
        }
    }

    private SignedDataVerifier verifierFor(Environment environment) {
        SignedDataVerifier verifier = environment == null ? null : verifiers.get(environment);
        if (verifier == null) {
            log.warn("Rejected a purchase from the {} environment, which isn't accepted here", environment);
            throw invalid(null);
        }
        return verifier;
    }

    /** What the unverified payload says its environment is. Only picks the verifier, which then checks it. */
    private Environment claimedEnvironment(String signed, String... path) {
        String[] parts = signed == null ? new String[0] : signed.split("\\.");
        if (parts.length != 3) {
            throw invalid(null);
        }
        try {
            JsonNode node = json.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            for (String field : path) {
                node = node.path(field);
            }
            return Environment.fromValue(node.asString(""));
        } catch (IllegalArgumentException | JacksonException e) {
            throw invalid(null);
        }
    }

    private static VerifiedTransaction toVerified(JWSTransactionDecodedPayload transaction) {
        return new VerifiedTransaction(transaction.getTransactionId(), transaction.getOriginalTransactionId(), transaction.getProductId(),
                transaction.getAppAccountToken(), instant(transaction.getPurchaseDate()), instant(transaction.getExpiresDate()), instant(transaction.getRevocationDate()),
                transaction.getEnvironment().name());
    }

    private static Instant instant(Long millis) {
        return millis == null ? null : Instant.ofEpochMilli(millis);
    }

    private static ApplicationException invalid(VerificationException cause) {
        if (cause != null) {
            log.warn("Rejected a purchase: {}", cause.getStatus(), cause);
        }
        return new ApplicationException("INVALID_TRANSACTION", "We couldn't confirm that purchase with Apple. Please try again.");
    }

    /** The streams are read once, by the verifier's constructor, so each verifier gets its own. */
    private static SignedDataVerifier verifier(List<byte[]> roots, AppStoreProperties properties, Environment environment) {
        Set<InputStream> streams = roots.stream().map(ByteArrayInputStream::new).collect(Collectors.toSet());
        return new SignedDataVerifier(streams, properties.bundleId(), properties.appAppleId(), environment, true);
    }

    private static byte[] read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Missing Apple root certificate " + path, e);
        }
    }
}
