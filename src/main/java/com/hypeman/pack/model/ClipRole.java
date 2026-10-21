package com.hypeman.pack.model;

/** The moment in a goal's life a clip plays for. Matches the role column's CHECK constraint and the app's ClipRole. */
public enum ClipRole {
    START,
    REMINDER,
    NAG,
    CONGRATS
}
