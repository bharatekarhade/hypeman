package com.hypeman.auth.controller;

import com.hypeman.auth.model.Device;
import com.hypeman.auth.service.DeviceService;
import com.hypeman.auth.service.TokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The app registers its push token for the device its access token was issued to. */
@RestController
@RequestMapping("/v1/devices")
class DeviceController {

    private final DeviceService devices;

    DeviceController(DeviceService devices) {
        this.devices = devices;
    }

    @PostMapping
    ResponseEntity<Void> register(@AuthenticationPrincipal Jwt token, @Valid @RequestBody PushTokenRequest body) {
        devices.registerPushToken(TokenService.userId(token), TokenService.deviceId(token), body.apnsToken(), body.environment());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{apnsToken}")
    ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt token, @PathVariable String apnsToken) {
        devices.removePushToken(TokenService.userId(token), apnsToken);
        return ResponseEntity.noContent().build();
    }

    record PushTokenRequest(@NotBlank @Size(max = 200) String apnsToken, @NotNull Device.ApnsEnvironment environment) {
    }
}
