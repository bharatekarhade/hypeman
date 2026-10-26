package com.hypeman.admin.repository;

import com.hypeman.admin.model.AuditEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEntryRepository extends JpaRepository<AuditEntry, UUID> {

    List<AuditEntry> findAllByOrderByCreatedAtDescIdDesc(Limit limit);
}
