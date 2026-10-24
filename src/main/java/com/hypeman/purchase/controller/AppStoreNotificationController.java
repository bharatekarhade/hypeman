package com.hypeman.purchase.controller;

import com.hypeman.purchase.service.PurchaseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * App Store Server Notifications V2: renewals, expiries, refunds and revocations. Public, since Apple doesn't sign in;
 * the payload's signature is what's trusted. Set this URL in App Store Connect for both Production and Sandbox.
 */
@RestController
class AppStoreNotificationController {

    private final PurchaseService purchases;

    AppStoreNotificationController(PurchaseService purchases) {
        this.purchases = purchases;
    }

    /** Anything but a 2xx makes Apple retry, so only a payload that fails verification gets an error. */
    @PostMapping("/v1/app-store/notifications")
    ResponseEntity<Void> notify(@Valid @RequestBody Notification body) {
        purchases.handleNotification(body.signedPayload());
        return ResponseEntity.ok().build();
    }

    record Notification(@NotBlank String signedPayload) {
    }
}
