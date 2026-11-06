package com.hypeman.auth.repository;

import com.hypeman.auth.model.Device;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** A user's devices, and their refresh tokens with them, go when the user does (ON DELETE CASCADE). */
public interface DeviceRepository extends JpaRepository<Device, UUID> {
}
