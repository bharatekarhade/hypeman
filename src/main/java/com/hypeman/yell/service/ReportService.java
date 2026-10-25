package com.hypeman.yell.service;

import com.hypeman.common.ApplicationException;
import com.hypeman.common.Ids;
import com.hypeman.yell.model.Report;
import com.hypeman.yell.model.ReportReason;
import com.hypeman.yell.model.YellDelivery;
import com.hypeman.yell.repository.ReportRepository;
import com.hypeman.yell.repository.YellDeliveryRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reports on received yells (FD-04). The yell leaves the reporter's inbox at once; moderators see the report. */
@Service
public class ReportService {

    private final ReportRepository reports;
    private final YellDeliveryRepository deliveries;
    private final Clock clock;

    ReportService(ReportRepository reports, YellDeliveryRepository deliveries, Clock clock) {
        this.reports = reports;
        this.deliveries = deliveries;
        this.clock = clock;
    }

    /** Only a recipient can report a yell. Reporting it again changes nothing. */
    @Transactional
    public void report(UUID userId, UUID yellId, ReportReason reason) {
        YellDelivery delivery = deliveries.findByYellIdAndRecipientId(yellId, userId)
                .orElseThrow(() -> new ApplicationException("YELL_NOT_FOUND", "That yell isn't one you received."));
        if (!reports.existsByReporterIdAndYellId(userId, yellId)) {
            reports.save(new Report(Ids.uuidV7(clock), userId, yellId, reason));
        }
        delivery.hide(clock.instant());
    }
}
