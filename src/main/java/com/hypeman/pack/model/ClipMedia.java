package com.hypeman.pack.model;

import java.net.URI;

/**
 * Short-lived signed download links for one clip.
 *
 * @param alarmUrl the notification sound (CAF), or null for a clip processed before alarms existed
 */
public record ClipMedia(ClipRole role, String caption, URI videoUrl, URI alarmUrl) {
}
