package com.hypeman.user;

import com.hypeman.common.ApiException;
import com.hypeman.common.Ids;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final IdentityRevoker revoker;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    UserService(UserRepository users, UserIdentityRepository identities, IdentityRevoker revoker, ApplicationEventPublisher events, Clock clock) {
        this.users = users;
        this.identities = identities;
        this.revoker = revoker;
        this.events = events;
        this.clock = clock;
    }

    /**
     * The user behind a verified Apple or Google account, created on first sign-in. Never matched by email. Two first
     * sign-ins racing with the same account get the same user: the loser drops the user it made and takes the winner's.
     */
    @Transactional
    public User findOrCreate(IdentityProvider provider, String subject, String email) {
        return identities.findByProviderAndSubject(provider, subject)
                .map(identity -> {
                    identity.updateEmail(email);
                    return users.findById(identity.getUserId()).orElseThrow();
                })
                .orElseGet(() -> {
                    User user = users.saveAndFlush(new User());
                    if (identities.insertIfAbsent(Ids.uuidV7(clock), user.getId(), provider.name(), subject, email) == 1) {
                        return user;
                    }
                    users.delete(user);
                    UserIdentity winner = identities.findByProviderAndSubject(provider, subject).orElseThrow();
                    return users.findById(winner.getUserId()).orElseThrow();
                });
    }

    /** A signed-in user. An access token can outlive its account by up to 15 minutes, so this checks. */
    @Transactional(readOnly = true)
    public User get(UUID userId) {
        return users.findById(userId)
                .filter(user -> !user.isDeleted())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DELETED", "This account was deleted."));
    }

    @Transactional(readOnly = true)
    public List<UserIdentity> identities(UUID userId) {
        return identities.findByUserIdOrderByProvider(userId);
    }

    /**
     * Deletes an account at once from the user's point of view: their sign-in with Apple is revoked, they are signed out
     * everywhere and their Apple or Google account is free to start a new one. The user row stays, marked deleted,
     * until the purge job removes it. If the revocation fails, nothing is deleted.
     */
    @Transactional
    public void delete(UUID userId, String appleAuthorizationCode) {
        User user = get(userId);
        revoker.revoke(identities.findByUserIdOrderByProvider(userId), appleAuthorizationCode);
        user.markDeleted(clock.instant());
        identities.deleteByUserId(userId);
        events.publishEvent(new UserDeletedEvent(userId));
    }
}
