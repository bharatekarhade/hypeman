package com.hypeman.purchase.controller;

import com.hypeman.auth.service.TokenService;
import com.hypeman.purchase.model.Entitlements;
import com.hypeman.purchase.service.PurchaseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The app reports each StoreKit purchase and restore here, and asks what the user owns. */
@RestController
@RequestMapping("/v1/purchases")
class PurchaseController {

    private final PurchaseService purchases;

    PurchaseController(PurchaseService purchases) {
        this.purchases = purchases;
    }

    @GetMapping
    Entitlements entitlements(@AuthenticationPrincipal Jwt token) {
        return purchases.entitlements(TokenService.userId(token));
    }

    /** @return what the user owns now, with this purchase */
    @PostMapping
    Entitlements record(@AuthenticationPrincipal Jwt token, @Valid @RequestBody PurchaseRequest body) {
        return purchases.record(TokenService.userId(token), body.signedTransaction());
    }

    /** @param signedTransaction StoreKit's jwsRepresentation of the transaction */
    record PurchaseRequest(@NotBlank String signedTransaction) {
    }
}
