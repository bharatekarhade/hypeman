package com.hypeman.user.model;

/** Who vouches for a user's identity. Matches the provider column's CHECK constraint. */
public enum IdentityProvider {
    APPLE,
    GOOGLE
}
