package com.hypeman.auth.repository;

import com.hypeman.auth.model.Device;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    /** Their refresh tokens go with them through the foreign key's ON DELETE CASCADE. */
    @Modifying
    @Query("delete from Device d where d.userId = :userId")
    void deleteByUserId(UUID userId);
}
