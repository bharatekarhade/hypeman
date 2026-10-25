package com.hypeman.auth.service;

import com.hypeman.auth.model.Device;
import com.hypeman.auth.repository.DeviceRepository;
import com.hypeman.common.ApplicationException;
import com.hypeman.user.model.UserDeletedEvent;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.context.event.EventListener;
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
                .orElseThrow(() -> new ApplicationException("DEVICE_SIGNED_OUT", "This device was signed out. Please sign in again."));
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

    /** Where to push for these users: their devices that allowed notifications. */
    @Transactional(readOnly = true)
    public List<PushTarget> pushTargets(Collection<UUID> userIds) {
        return devices.findByUserIdInAndApnsTokenIsNotNull(userIds).stream()
                .map(device -> new PushTarget(device.getApnsToken(), device.getEnvironment()))
                .toList();
    }

    /** APNs says the token will never work again, usually because the app was deleted. */
    @Transactional
    public void dropPushToken(String apnsToken) {
        devices.findByApnsToken(apnsToken).ifPresent(Device::clearPushToken);
    }

    /** Signs a deleted user out everywhere; their refresh tokens go with the devices. */
    @EventListener
    void onUserDeleted(UserDeletedEvent event) {
        devices.deleteByUserId(event.userId());
    }

    public record PushTarget(String apnsToken, Device.ApnsEnvironment environment) {
    }
}
