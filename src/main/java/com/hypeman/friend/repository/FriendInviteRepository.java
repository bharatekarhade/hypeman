package com.hypeman.friend.repository;

import com.hypeman.friend.model.FriendInvite;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface FriendInviteRepository extends JpaRepository<FriendInvite, UUID> {

    boolean existsByCode(String code);

    /** Held until the transaction ends, so two people can't both accept a one-use code. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FriendInvite> findLockedByCode(String code);

    @Modifying
    @Query("delete from FriendInvite i where i.inviterId = :userId and i.acceptedAt is null")
    void deleteOpenByInviterId(UUID userId);
}
