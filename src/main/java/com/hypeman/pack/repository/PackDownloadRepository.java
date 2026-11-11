package com.hypeman.pack.repository;

import com.hypeman.pack.model.PackDownload;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackDownloadRepository extends JpaRepository<PackDownload, UUID> {

    long countByPackId(UUID packId);
}
