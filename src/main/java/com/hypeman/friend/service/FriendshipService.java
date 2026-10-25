package com.hypeman.friend.service;

import com.hypeman.common.ApplicationException;
import com.hypeman.friend.model.Block;
import com.hypeman.friend.model.Friendship;
import com.hypeman.friend.repository.BlockRepository;
import com.hypeman.friend.repository.FriendInviteRepository;
import com.hypeman.friend.repository.FriendshipRepository;
import com.hypeman.user.model.UserDeletedEvent;
import com.hypeman.user.model.UserView;
import com.hypeman.user.service.UserService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Who can yell at whom. Only friends exchange yells, and a block ends a friendship for good (FD-04, FD-05). */
@Service
public class FriendshipService {

    private final FriendshipRepository friendships;
    private final BlockRepository blocks;
    private final FriendInviteRepository invites;
    private final UserService users;

    FriendshipService(FriendshipRepository friendships, BlockRepository blocks, FriendInviteRepository invites, UserService users) {
        this.friendships = friendships;
        this.blocks = blocks;
        this.invites = invites;
        this.users = users;
    }

    /** Oldest friend first. Friends whose accounts were deleted are left out. */
    @Transactional(readOnly = true)
    public List<UserView> friends(UUID userId) {
        List<UUID> ids = friendships.findAllOf(userId).stream().map(friendship -> friendship.other(userId)).toList();
        Map<UUID, UserView> views = users.views(ids);
        return ids.stream().filter(views::containsKey).map(views::get).toList();
    }

    @Transactional(readOnly = true)
    public boolean areFriends(UUID one, UUID other) {
        return friendships.existsById(Friendship.Key.of(one, other));
    }

    /** Either way round. */
    @Transactional(readOnly = true)
    public boolean isBlocked(UUID one, UUID other) {
        return blocks.existsBetween(one, other);
    }

    /** The users {@code userId} blocked, whose yells they no longer see. */
    @Transactional(readOnly = true)
    public List<UUID> blockedBy(UUID userId) {
        return blocks.findBlockedIds(userId);
    }

    /** Idempotent: already being friends is fine. */
    @Transactional
    public void befriend(UUID one, UUID other) {
        if (!areFriends(one, other)) {
            friendships.save(Friendship.between(one, other));
        }
    }

    /** Idempotent. After this the other user can't yell at {@code userId} until they're friends again. */
    @Transactional
    public void remove(UUID userId, UUID friendId) {
        friendships.deleteById(Friendship.Key.of(userId, friendId));
    }

    /** Ends the friendship and hides the blocked user's yells from {@code userId}. Idempotent. */
    @Transactional
    public void block(UUID userId, UUID blockedId) {
        if (userId.equals(blockedId)) {
            throw new ApplicationException("VALIDATION_FAILED", "You can't block yourself.");
        }
        if (users.views(List.of(blockedId)).isEmpty()) {
            throw new ApplicationException("USER_NOT_FOUND", "That person isn't on Hypeman.");
        }
        if (!blocks.existsById(new Block.Key(userId, blockedId))) {
            blocks.save(new Block(userId, blockedId));
        }
        remove(userId, blockedId);
    }

    /** A deleted user drops out of every friend list at once, and their open invites stop working. */
    @EventListener
    void onUserDeleted(UserDeletedEvent event) {
        friendships.deleteAllOf(event.userId());
        invites.deleteOpenByInviterId(event.userId());
    }
}
