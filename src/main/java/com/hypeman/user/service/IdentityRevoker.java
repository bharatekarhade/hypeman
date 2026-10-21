package com.hypeman.user.service;

import com.hypeman.user.model.UserIdentity;
import java.util.List;

/**
 * Revokes a user's sign-in with Apple when their account is deleted, as Apple requires. Implemented by the auth module,
 * so this module doesn't depend on it.
 */
public interface IdentityRevoker {

    /**
     * Throws, and nothing is deleted, if the sign-in can't be revoked.
     *
     * @param appleAuthorizationCode a fresh code from Sign in with Apple, needed when one of the identities is Apple's
     */
    void revoke(List<UserIdentity> identities, String appleAuthorizationCode);
}
