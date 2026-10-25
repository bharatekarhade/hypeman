package com.hypeman.yell.repository;

import com.hypeman.yell.model.Report;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    boolean existsByReporterIdAndYellId(UUID reporterId, UUID yellId);
}
