package com.hypeman.push.service;

import com.hypeman.auth.service.DeviceService;
import com.hypeman.auth.service.DeviceService.PushTarget;
import com.hypeman.push.model.PushMessage;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Pushes to every device the users are signed in on that allowed notifications. Tokens APNs reports dead are dropped. */
@Service
public class PushService {

    private final DeviceService devices;
    private final ApnsGateway gateway;

    PushService(DeviceService devices, ApnsGateway gateway) {
        this.devices = devices;
        this.gateway = gateway;
    }

    public void send(Collection<UUID> userIds, PushMessage message) {
        for (PushTarget target : devices.pushTargets(userIds)) {
            if (gateway.send(target.apnsToken(), target.environment(), message) == ApnsGateway.Result.DEAD_TOKEN) {
                devices.dropPushToken(target.apnsToken());
            }
        }
    }
}
