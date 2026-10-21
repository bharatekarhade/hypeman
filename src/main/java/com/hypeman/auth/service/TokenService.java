package com.hypeman.auth.service;

import com.hypeman.auth.config.AuthProperties;
import com.hypeman.auth.model.Device;
import com.hypeman.auth.model.RefreshToken;
import com.hypeman.auth.repository.DeviceRepository;
import com.hypeman.auth.repository.RefreshTokenRepository;
import com.hypeman.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Our own tokens: a short-lived access token (a JWT the resource server checks on every request) and a refresh token
 * bound to one device, replaced on every use.
 */
@Service
public class TokenService {

    /** The access token's claim naming the device it was issued to. */
    static final String DEVICE_CLAIM = "did";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtEncoder encoder;
    private final RefreshTokenRepository tokens;
    private final DeviceRepository devices;
    private final AuthProperties properties;
    private final Clock clock;

    TokenService(JwtEncoder encoder, RefreshTokenRepository tokens, DeviceRepository devices, AuthProperties properties, Clock clock) {
        this.encoder = encoder;
        this.tokens = tokens;
        this.devices = devices;
        this.properties = properties;
        this.clock = clock;
    }

    public static UUID userId(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }

    public static UUID deviceId(Jwt accessToken) {
        return UUID.fromString(accessToken.getClaimAsString(DEVICE_CLAIM));
    }

    /** Signs a user in on a device. If someone else was signed in on it, they are signed out there. */
    @Transactional
    public TokenPair signIn(UUID userId, UUID deviceId) {
        Instant now = clock.instant();
        devices.findById(deviceId).ifPresentOrElse(device -> {
            if (!device.getUserId().equals(userId)) {
                tokens.revokeAllForDevice(deviceId, now);
                device.moveTo(userId);
            }
            device.seen(now);
        }, () -> devices.save(new Device(deviceId, userId, now)));
        return issue(userId, deviceId, now);
    }

    /**
     * Swaps a refresh token for a new pair, once. A token that was already swapped is being replayed, maybe from a
     * stolen copy, so every token for its device is revoked and the device has to sign in again. That holds for two
     * requests racing with the same token too: the revoke is atomic, one wins and the other counts as a replay. The
     * device-wide revocation commits even though the request fails.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenPair refresh(String refreshToken) {
        Instant now = clock.instant();
        RefreshToken token = tokens.findByTokenHash(hash(refreshToken)).orElseThrow(TokenService::invalidRefreshToken);
        if (!token.isRevoked() && token.isExpired(now)) {
            throw invalidRefreshToken();
        }
        if (token.isRevoked() || tokens.revokeIfActive(token.getId(), now) == 0) {
            tokens.revokeAllForDevice(token.getDeviceId(), now);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSED", "This sign-in was used somewhere else. Please sign in again.");
        }
        devices.findById(token.getDeviceId()).ifPresent(device -> device.seen(now));
        return issue(token.getUserId(), token.getDeviceId(), now);
    }

    /** Revokes the refresh token if it is the caller's. Unknown or foreign tokens are ignored, so logout always works. */
    @Transactional
    public void logout(UUID userId, String refreshToken) {
        tokens.findByTokenHash(hash(refreshToken))
                .filter(token -> token.getUserId().equals(userId))
                .ifPresent(token -> token.revoke(clock.instant()));
    }

    private TokenPair issue(UUID userId, UUID deviceId, Instant now) {
        Instant accessTokenExpiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(accessTokenExpiresAt)
                .claim(DEVICE_CLAIM, deviceId.toString())
                .build();
        String accessToken = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();

        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        tokens.save(new RefreshToken(userId, deviceId, hash(refreshToken), now.plus(properties.refreshTokenTtl())));
        return new TokenPair(accessToken, accessTokenExpiresAt, refreshToken);
    }

    private static byte[] hash(String refreshToken) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException invalidRefreshToken() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Your sign-in expired. Please sign in again.");
    }

    public record TokenPair(String accessToken, Instant accessTokenExpiresAt, String refreshToken) {
    }
}
