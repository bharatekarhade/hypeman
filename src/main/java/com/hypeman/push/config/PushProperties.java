package com.hypeman.push.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * An APNs auth key from the Apple developer account (Keys → Apple Push Notifications service). Unset, pushes are skipped.
 *
 * @param teamId the developer team id
 * @param keyId the key's id
 * @param privateKey the .p8 file's contents
 * @param topic the app's bundle id
 */
@ConfigurationProperties("hypeman.push")
public record PushProperties(String teamId, String keyId, String privateKey, String topic) {

    public boolean isConfigured() {
        return teamId != null && !teamId.isBlank() && keyId != null && !keyId.isBlank() && privateKey != null && !privateKey.isBlank();
    }
}
