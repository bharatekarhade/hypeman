package com.hypeman.yell.repository;

import com.hypeman.yell.model.Yell;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface YellRepository extends JpaRepository<Yell, UUID> {

    Optional<Yell> findByIdAndSenderId(UUID id, UUID senderId);

    long countBySenderIdAndSentAtGreaterThanEqual(UUID senderId, Instant since);

    @Modifying
    @Query("update Yell y set y.status = com.hypeman.yell.model.YellStatus.REMOVED where y.senderId = :senderId")
    void removeAllBy(UUID senderId);
}
