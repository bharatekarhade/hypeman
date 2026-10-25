package com.hypeman.yell.repository;

import com.hypeman.yell.model.InboxRow;
import com.hypeman.yell.model.YellDelivery;
import com.hypeman.yell.model.YellStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface YellDeliveryRepository extends JpaRepository<YellDelivery, UUID> {

    Optional<YellDelivery> findByIdAndRecipientId(UUID id, UUID recipientId);

    Optional<YellDelivery> findByYellIdAndRecipientId(UUID yellId, UUID recipientId);

    boolean existsByYellIdAndRecipientId(UUID yellId, UUID recipientId);

    /** Newest first. {@code hiddenSenders} is never empty: callers pass a placeholder when nobody is blocked. */
    @Query("""
            select new com.hypeman.yell.model.InboxRow(d, y) from YellDelivery d join Yell y on y.id = d.yellId
            where d.recipientId = :recipientId and d.hiddenAt is null and y.status = :status and y.senderId not in :hiddenSenders
            order by d.createdAt desc, d.id desc""")
    List<InboxRow> inbox(UUID recipientId, YellStatus status, Collection<UUID> hiddenSenders, Limit limit);

    /** The page after the entry at ({@code createdAt}, {@code id}). */
    @Query("""
            select new com.hypeman.yell.model.InboxRow(d, y) from YellDelivery d join Yell y on y.id = d.yellId
            where d.recipientId = :recipientId and d.hiddenAt is null and y.status = :status and y.senderId not in :hiddenSenders
              and (d.createdAt < :createdAt or (d.createdAt = :createdAt and d.id < :id))
            order by d.createdAt desc, d.id desc""")
    List<InboxRow> inboxAfter(UUID recipientId, YellStatus status, Collection<UUID> hiddenSenders, Instant createdAt, UUID id, Limit limit);
}
