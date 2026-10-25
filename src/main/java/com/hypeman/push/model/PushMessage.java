package com.hypeman.push.model;

import java.util.Map;

/**
 * An alert for the app.
 *
 * @param data extra keys the app reads when the notification is tapped
 */
public record PushMessage(String title, String body, Map<String, String> data) {
}
