package com.hypeman.friend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param inviteTtl how long an invite code works
 * @param inviteLinkBase the start of a shareable invite link; the code goes on the end
 */
@ConfigurationProperties("hypeman.friends")
public record FriendProperties(Duration inviteTtl, String inviteLinkBase) {
}
