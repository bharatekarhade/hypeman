package com.hypeman.pack.model;

/** Matches the status column's CHECK constraint on packs. */
public enum PackStatus {
    DRAFT,
    IN_REVIEW,
    PUBLISHED,
    REJECTED,
    REMOVED
}
