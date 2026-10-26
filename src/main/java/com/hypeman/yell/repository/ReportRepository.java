package com.hypeman.yell.repository;

import com.hypeman.yell.model.Report;
import com.hypeman.yell.model.ReportStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    boolean existsByReporterIdAndYellId(UUID reporterId, UUID yellId);

    /** The moderation queue: yells with open reports, the longest waiting first. */
    @Query("""
            select r.yellId from Report r where r.status = com.hypeman.yell.model.ReportStatus.OPEN
            group by r.yellId order by min(r.createdAt), r.yellId""")
    List<UUID> findReportedYellIds(Limit limit);

    List<Report> findByYellIdInAndStatusOrderByCreatedAt(Collection<UUID> yellIds, ReportStatus status);

    /** How many of the sender's yells moderators have taken down before. */
    @Query("""
            select count(distinct r.yellId) from Report r
            where r.status = com.hypeman.yell.model.ReportStatus.REMOVED
            and r.yellId in (select y.id from Yell y where y.senderId = :senderId)""")
    long countTakedownsOf(UUID senderId);

    @Modifying(flushAutomatically = true)
    @Query("""
            update Report r set r.status = :status, r.handledAt = :now
            where r.yellId = :yellId and r.status = com.hypeman.yell.model.ReportStatus.OPEN""")
    int closeOpen(UUID yellId, ReportStatus status, Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update Report r set r.status = com.hypeman.yell.model.ReportStatus.REMOVED, r.handledAt = :now
            where r.status = com.hypeman.yell.model.ReportStatus.OPEN
            and r.yellId in (select y.id from Yell y where y.senderId = :senderId)""")
    int closeOpenOf(UUID senderId, Instant now);
}
