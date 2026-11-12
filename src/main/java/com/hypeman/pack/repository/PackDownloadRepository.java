package com.hypeman.pack.repository;

import com.hypeman.pack.model.PackDownload;
import com.hypeman.pack.model.PackDownloadStats;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PackDownloadRepository extends JpaRepository<PackDownload, UUID> {

    long countByPackId(UUID packId);

    /** Packs nobody downloaded since then aren't in the list. */
    @Query("""
            select new com.hypeman.pack.model.PackDownloadStats(d.packId, count(d), count(distinct d.userId), max(d.downloadedAt))
            from PackDownload d
            where d.downloadedAt >= :since
            group by d.packId""")
    List<PackDownloadStats> statsSince(Instant since);
}
