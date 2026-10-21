package com.hypeman.pack.model;

import java.net.URI;

/** @param videoUrl a short-lived signed download link */
public record ClipMedia(ClipRole role, String caption, URI videoUrl) {
}
