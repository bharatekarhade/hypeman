package com.hypeman.auth.service;

import com.hypeman.auth.repository.DeviceRepository;
import com.hypeman.user.model.UserDeletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** The devices users are signed in on. */
@Service
public class DeviceService {

    private final DeviceRepository devices;

    DeviceService(DeviceRepository devices) {
        this.devices = devices;
    }

    /** Signs a deleted user out everywhere; their refresh tokens go with the devices. */
    @EventListener
    void onUserDeleted(UserDeletedEvent event) {
        devices.deleteByUserId(event.userId());
    }
}
