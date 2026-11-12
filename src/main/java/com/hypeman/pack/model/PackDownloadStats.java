package com.hypeman.pack.model;

import java.time.Instant;
import java.util.UUID;

/**
 * How a pack is doing.
 *
 * @param downloads        every download, by anyone, including people who have since deleted their account
 * @param people           how many accounts that still exist downloaded it
 * @param lastDownloadedAt null if nobody has
 */
public record PackDownloadStats(UUID packId, long downloads, long people, Instant lastDownloadedAt) {

    public static PackDownloadStats none(UUID packId) {
        return new PackDownloadStats(packId, 0, 0, null);
    }
}
