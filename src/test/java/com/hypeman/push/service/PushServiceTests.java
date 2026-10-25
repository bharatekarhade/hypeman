package com.hypeman.push.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hypeman.auth.model.Device.ApnsEnvironment;
import com.hypeman.auth.service.DeviceService;
import com.hypeman.auth.service.DeviceService.PushTarget;
import com.hypeman.push.model.PushMessage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PushServiceTests {

    private final DeviceService devices = mock(DeviceService.class);
    private final ApnsGateway gateway = mock(ApnsGateway.class);
    private final PushService push = new PushService(devices, gateway);
    private final PushMessage message = new PushMessage("Coach yelled at you", "“Up!”", Map.of());

    @Test
    void everyDeviceGetsItAndDeadTokensAreDropped() {
        UUID user = UUID.randomUUID();
        when(devices.pushTargets(List.of(user))).thenReturn(List.of(
                new PushTarget("live", ApnsEnvironment.PRODUCTION), new PushTarget("gone", ApnsEnvironment.SANDBOX)));
        when(gateway.send("live", ApnsEnvironment.PRODUCTION, message)).thenReturn(ApnsGateway.Result.ACCEPTED);
        when(gateway.send("gone", ApnsEnvironment.SANDBOX, message)).thenReturn(ApnsGateway.Result.DEAD_TOKEN);

        push.send(List.of(user), message);

        verify(devices).dropPushToken("gone");
        verify(devices, never()).dropPushToken("live");
    }

    @Test
    void aFailureKeepsTheToken() {
        UUID user = UUID.randomUUID();
        when(devices.pushTargets(List.of(user))).thenReturn(List.of(new PushTarget("flaky", ApnsEnvironment.PRODUCTION)));
        when(gateway.send(eq("flaky"), any(), any())).thenReturn(ApnsGateway.Result.FAILED);

        push.send(List.of(user), message);

        verify(devices, never()).dropPushToken(any());
    }
}
