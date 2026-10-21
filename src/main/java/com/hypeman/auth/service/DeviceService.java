package com.hypeman.auth.service;

import com.hypeman.auth.model.Device;
import com.hypeman.auth.repository.DeviceRepository;
import com.hypeman.common.ApiException;
import com.hypeman.user.model.UserDeletedEvent;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Push tokens for the devices users are signed in on. */
@Service
public class DeviceService {

    private final DeviceRepository devices;

    DeviceService(DeviceRepository devices) {
        this.devices = devices;
    }

    /** A push token belongs to one install, so it is taken off any other device row that still has it. */
    @Transactional
    public void registerPushToken(UUID userId, UUID deviceId, String apnsToken, Device.ApnsEnvironment environment) {
        Device device = devices.findById(deviceId)
                .filter(d -> d.getUserId().equals(userId))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "DEVICE_SIGNED_OUT", "This device was signed out. Please sign in again."));
        devices.findByApnsToken(apnsToken)
                .filter(other -> !other.getId().equals(deviceId))
                .ifPresent(other -> {
                    other.clearPushToken();
                    devices.flush();
                });
        device.setPushToken(apnsToken, environment);
    }

    @Transactional
    public void removePushToken(UUID userId, String apnsToken) {
        devices.findByApnsTokenAndUserId(apnsToken, userId).ifPresent(Device::clearPushToken);
    }

    /** Signs a deleted user out everywhere; their refresh tokens go with the devices. */
    @EventListener
    void onUserDeleted(UserDeletedEvent event) {
        devices.deleteByUserId(event.userId());
    }
}
