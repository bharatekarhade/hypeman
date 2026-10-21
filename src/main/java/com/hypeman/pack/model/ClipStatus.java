package com.hypeman.pack.model;

/** Where a clip is in upload and processing. Matches the status column's CHECK constraint on clips. */
public enum ClipStatus {
    UPLOADING,
    PROCESSING,
    READY,
    FAILED
}
