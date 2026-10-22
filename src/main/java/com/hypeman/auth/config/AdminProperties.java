package com.hypeman.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param token the shared secret for /v1/admin/**, sent as a bearer token, from HYPEMAN_ADMIN_TOKEN; when blank the admin
 *     endpoints turn every request away
 */
@ConfigurationProperties("hypeman.admin")
public record AdminProperties(String token) {
}
