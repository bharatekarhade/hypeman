package com.hypeman.common;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * UUIDv7 (RFC 9562) for rows written with plain SQL; entities get theirs from Hibernate's generator. A 48-bit Unix
 * millisecond timestamp first, so ids sort by creation time, then random bits.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID uuidV7(Clock clock) {
        long millis = clock.millis();
        long randomHigh = RANDOM.nextLong();
        long randomLow = RANDOM.nextLong();
        long mostSignificant = (millis << 16) | 0x7000L | (randomHigh & 0x0FFFL);
        long leastSignificant = (randomLow & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
