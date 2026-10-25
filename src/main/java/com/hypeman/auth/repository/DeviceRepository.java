package com.hypeman.auth.repository;

import com.hypeman.auth.model.Device;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    Optional<Device> findByApnsToken(String apnsToken);

    Optional<Device> findByApnsTokenAndUserId(String apnsToken, UUID userId);

    List<Device> findByUserIdInAndApnsTokenIsNotNull(Collection<UUID> userIds);

    /** Their refresh tokens go with them through the foreign key's ON DELETE CASCADE. */
    @Modifying
    @Query("delete from Device d where d.userId = :userId")
    void deleteByUserId(UUID userId);
}
