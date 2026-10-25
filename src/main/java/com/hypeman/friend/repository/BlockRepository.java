package com.hypeman.friend.repository;

import com.hypeman.friend.model.Block;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BlockRepository extends JpaRepository<Block, Block.Key> {

    @Query("""
            select count(b) > 0 from Block b
            where (b.blockerId = :one and b.blockedId = :other) or (b.blockerId = :other and b.blockedId = :one)""")
    boolean existsBetween(UUID one, UUID other);

    @Query("select b.blockedId from Block b where b.blockerId = :userId")
    List<UUID> findBlockedIds(UUID userId);
}
