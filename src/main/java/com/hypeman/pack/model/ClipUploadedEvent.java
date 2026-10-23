package com.hypeman.pack.model;

import java.util.UUID;

/** A clip's original reached S3 and is waiting to be processed. Published inside the transaction that marked it PROCESSING. */
public record ClipUploadedEvent(UUID clipId, String sourceKey) {
}
