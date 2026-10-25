package com.hypeman.friend.repository;

import com.hypeman.friend.model.Friendship;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface FriendshipRepository extends JpaRepository<Friendship, Friendship.Key> {

    @Query("select f from Friendship f where f.userA = :userId or f.userB = :userId order by f.createdAt")
    List<Friendship> findAllOf(UUID userId);

    @Modifying
    @Query("delete from Friendship f where f.userA = :userId or f.userB = :userId")
    void deleteAllOf(UUID userId);
}
