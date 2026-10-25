package com.hypeman.push.service;

import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.ApnsClientBuilder;
import com.eatthepath.pushy.apns.PushNotificationResponse;
import com.eatthepath.pushy.apns.auth.ApnsSigningKey;
import com.eatthepath.pushy.apns.util.ApnsPayloadBuilder;
import com.eatthepath.pushy.apns.util.SimpleApnsPayloadBuilder;
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification;
import com.hypeman.auth.model.Device.ApnsEnvironment;
import com.hypeman.push.config.PushProperties;
import com.hypeman.push.model.PushMessage;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Talks to APNs with Pushy, one client per environment, made on first use. */
@Component
public class ApnsGateway {

    private static final Logger log = LoggerFactory.getLogger(ApnsGateway.class);
    /** APNs' reasons for a token that will never work again. */
    private static final Set<String> DEAD_TOKEN = Set.of("BadDeviceToken", "Unregistered", "DeviceTokenNotForTopic");

    private final PushProperties properties;
    private final Map<ApnsEnvironment, ApnsClient> clients = new EnumMap<>(ApnsEnvironment.class);

    ApnsGateway(PushProperties properties) {
        this.properties = properties;
        if (!properties.isConfigured()) {
            log.warn("hypeman.push isn't set up, so push notifications are skipped");
        }
    }

    public Result send(String token, ApnsEnvironment environment, PushMessage message) {
        if (!properties.isConfigured()) {
            return Result.SKIPPED;
        }
        ApnsPayloadBuilder payload = new SimpleApnsPayloadBuilder()
                .setAlertTitle(message.title())
                .setAlertBody(message.body())
                .setSound(ApnsPayloadBuilder.DEFAULT_SOUND_FILENAME);
        message.data().forEach(payload::addCustomProperty);
        try {
            PushNotificationResponse<SimpleApnsPushNotification> response = client(environment)
                    .sendNotification(new SimpleApnsPushNotification(token, properties.topic(), payload.build()))
                    .get(10, TimeUnit.SECONDS);
            if (response.isAccepted()) {
                return Result.ACCEPTED;
            }
            String reason = response.getRejectionReason().orElse("unknown");
            log.info("APNs rejected a push: {}", reason);
            return DEAD_TOKEN.contains(reason) ? Result.DEAD_TOKEN : Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            log.warn("Could not send a push", e);
            return Result.FAILED;
        }
    }

    private synchronized ApnsClient client(ApnsEnvironment environment) throws ExecutionException {
        ApnsClient client = clients.get(environment);
        if (client == null) {
            try {
                client = new ApnsClientBuilder()
                        .setApnsServer(environment == ApnsEnvironment.PRODUCTION
                                ? ApnsClientBuilder.PRODUCTION_APNS_HOST : ApnsClientBuilder.DEVELOPMENT_APNS_HOST)
                        .setSigningKey(ApnsSigningKey.loadFromInputStream(
                                new ByteArrayInputStream(properties.privateKey().getBytes(StandardCharsets.UTF_8)),
                                properties.teamId(), properties.keyId()))
                        .build();
            } catch (Exception e) {
                throw new ExecutionException("Could not set up APNs; check hypeman.push", e);
            }
            clients.put(environment, client);
        }
        return client;
    }

    @PreDestroy
    synchronized void close() {
        clients.values().forEach(ApnsClient::close);
    }

    public enum Result {
        ACCEPTED,
        /** The app was deleted or the token is otherwise gone for good. */
        DEAD_TOKEN,
        FAILED,
        /** Pushes aren't configured. */
        SKIPPED
    }
}
