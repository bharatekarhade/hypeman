package com.hypeman.friend.service;

import com.hypeman.common.ApplicationException;
import com.hypeman.common.Ids;
import com.hypeman.friend.config.FriendProperties;
import com.hypeman.friend.model.FriendInvite;
import com.hypeman.friend.repository.FriendInviteRepository;
import com.hypeman.user.model.UserView;
import com.hypeman.user.service.UserService;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Invite codes and links (FD-01). A code adds one friend, then it's used up. */
@Service
public class InviteService {

    /** No 0/O, 1/I/L: people read these out and type them. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final FriendInviteRepository invites;
    private final FriendshipService friendships;
    private final UserService users;
    private final FriendProperties properties;
    private final Clock clock;

    InviteService(FriendInviteRepository invites, FriendshipService friendships, UserService users, FriendProperties properties, Clock clock) {
        this.invites = invites;
        this.friendships = friendships;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Invite create(UUID userId) {
        users.get(userId);
        String code;
        do {
            code = randomCode();
        } while (invites.existsByCode(code));
        FriendInvite invite = invites.save(new FriendInvite(Ids.uuidV7(clock), userId, code, clock.instant().plus(properties.inviteTtl())));
        return new Invite(invite.getCode(), properties.inviteLinkBase() + invite.getCode(), invite.getExpiresAt());
    }

    /**
     * Makes the inviter and {@code userId} friends. Accepting the same invite again is fine; someone else's used,
     * expired or unknown code isn't. An invite between people where one blocked the other looks like an unknown code.
     *
     * @return the new friend
     */
    @Transactional
    public UserView accept(UUID userId, String rawCode) {
        String code = rawCode.strip().toUpperCase(Locale.ROOT);
        FriendInvite invite = invites.findLockedByCode(code).orElseThrow(InviteService::notFound);
        if (invite.getInviterId().equals(userId)) {
            throw new ApplicationException("INVITE_OWN", "That's your own invite. Send it to a friend instead.");
        }
        if (invite.getAcceptedBy() != null && !invite.getAcceptedBy().equals(userId)) {
            throw new ApplicationException("INVITE_USED", "Someone already used that invite. Ask your friend for a new one.");
        }
        Instant now = clock.instant();
        if (invite.getAcceptedBy() == null && invite.isExpiredAt(now)) {
            throw new ApplicationException("INVITE_EXPIRED", "That invite expired. Ask your friend for a new one.");
        }
        UserView inviter = users.views(List.of(invite.getInviterId())).get(invite.getInviterId());
        if (inviter == null || friendships.isBlocked(userId, invite.getInviterId())) {
            throw notFound();
        }
        if (invite.getAcceptedBy() == null) {
            invite.accept(userId, now);
        }
        friendships.befriend(userId, invite.getInviterId());
        return inviter;
    }

    private static String randomCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    private static ApplicationException notFound() {
        return new ApplicationException("INVITE_NOT_FOUND", "That invite code doesn't work. Check it, or ask your friend for a new one.");
    }

    /** @param link a shareable link that opens the app on this invite */
    public record Invite(String code, String link, Instant expiresAt) {
    }
}
