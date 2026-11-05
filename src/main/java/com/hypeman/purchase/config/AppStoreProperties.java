package com.hypeman.purchase.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param bundleId the app's bundle id; transactions for any other app are rejected
 * @param appAppleId the app's Apple ID in App Store Connect; production transactions are only accepted once it's set
 * @param acceptXcodeTransactions accept transactions from Xcode's StoreKit testing, which nobody signs. Local
 *     development only: with it on, anyone can make up a purchase
 */
@ConfigurationProperties("hypeman.app-store")
public record AppStoreProperties(String bundleId, Long appAppleId, boolean acceptXcodeTransactions) {
}
