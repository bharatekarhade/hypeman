package com.hypeman.pack.model;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ClipUpload(UUID clipId, URI uploadUrl, Map<String,String> headers, Instant expiresAt) {
}
