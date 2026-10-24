package com.hypeman.purchase.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param bundleId the app's bundle id; transactions for any other app are rejected
 * @param appAppleId the app's Apple ID in App Store Connect; production transactions are only accepted once it's set
 * @param proProductIds the subscriptions that make a user Pro; every other product is a pack's product id
 * @param acceptXcodeTransactions accept transactions from Xcode's StoreKit testing, which nobody signs. Local
 *     development only: with it on, anyone can make up a purchase
 */
@ConfigurationProperties("hypeman.app-store")
public record AppStoreProperties(String bundleId, Long appAppleId, List<String> proProductIds, boolean acceptXcodeTransactions) {

    public AppStoreProperties {
        proProductIds = proProductIds == null ? List.of() : List.copyOf(proProductIds);
    }
}
