package com.hypeman.auth.service;

import com.hypeman.common.ApplicationException;

/** Signals token replay; the surrounding transaction must commit device-wide token revocation. */
final class RefreshTokenReuseException extends ApplicationException {

    RefreshTokenReuseException() {
        super("REFRESH_TOKEN_REUSED", "This sign-in was used somewhere else. Please sign in again.");
    }
}
