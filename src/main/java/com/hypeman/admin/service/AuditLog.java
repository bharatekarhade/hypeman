package com.hypeman.admin.service;

import com.hypeman.admin.model.AuditEntry;
import com.hypeman.admin.repository.AuditEntryRepository;
import com.hypeman.common.Ids;
import java.time.Clock;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Every admin action, written once it has succeeded, with whoever is signed in to the admin endpoints as the actor. */
@Service
public class AuditLog {

    private final AuditEntryRepository entries;
    private final Clock clock;

    AuditLog(AuditEntryRepository entries, Clock clock) {
        this.entries = entries;
        this.clock = clock;
    }

    /**
     * @param action what was done, such as "user.ban"
     * @param target what it was done to, such as "user:&lt;id&gt;"
     * @param detail the admin's reason or note; may be null
     */
    @Transactional
    public void record(String action, String target, String detail) {
        Authentication actor = SecurityContextHolder.getContext().getAuthentication();
        entries.save(new AuditEntry(Ids.uuidV7(clock), actor != null ? actor.getName() : "system", action, target, detail));
    }

    /** Newest first. */
    @Transactional(readOnly = true)
    public List<AuditEntry> recent(int limit) {
        return entries.findAllByOrderByCreatedAtDescIdDesc(Limit.of(limit));
    }
}
