package com.hypeman.media.service;

import com.hypeman.common.ApplicationException;
import com.hypeman.media.config.MediaProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Media files in S3. The app's uploads and downloads never pass through the backend: callers get a short-lived presigned
 * link and talk to S3 directly. The bucket is private; a link is the only way in. Only clip processing moves files
 * through the server itself.
 */
@Service
public class MediaStorage {

    private static final Logger log = LoggerFactory.getLogger(MediaStorage.class);
    /** Folder-style keys such as packs/0199.../start/source.mp4: no leading slash, no "..", no spaces. */
    private static final Pattern VALID_KEY = Pattern.compile("[a-z0-9][a-z0-9_\\-]*(/[a-zA-Z0-9][a-zA-Z0-9_\\-.]*)*");

    private final S3Presigner presigner;
    private final S3Client s3;
    private final MediaProperties properties;
    private final Clock clock;

    MediaStorage(S3Presigner presigner, S3Client s3, MediaProperties properties, Clock clock) {
        this.presigner = presigner;
        this.s3 = s3;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * A link to PUT one file at {@code key}. The content type and exact size are part of the signature, so S3 rejects
     * an upload that doesn't match what was asked for.
     */
    public PresignedUpload presignUpload(String key, String contentType, long sizeBytes) {
        checkKey(key);
        if (sizeBytes <= 0 || sizeBytes > properties.maxUploadSize().toBytes()) {
            throw new ApplicationException("UPLOAD_TOO_LARGE",
                    "Files can be up to " + properties.maxUploadSize().toMegabytes() + " MB.");
        }
        Instant expiresAt = clock.instant().plus(properties.uploadUrlTtl());
        try {
            var request = presigner.presignPutObject(presign -> presign
                    .signatureDuration(properties.uploadUrlTtl())
                    .putObjectRequest(put -> put.bucket(bucket()).key(key).contentType(contentType).contentLength(sizeBytes)));
            return new PresignedUpload(request.url().toURI(), Map.of("Content-Type", contentType), expiresAt);
        } catch (SdkException | URISyntaxException e) {
            throw unavailable(e);
        }
    }

    /** A link to GET the file at {@code key}, valid for the download TTL. */
    public URI presignDownload(String key) {
        checkKey(key);
        try {
            return presigner.presignGetObject(presign -> presign
                    .signatureDuration(properties.downloadUrlTtl())
                    .getObjectRequest(get -> get.bucket(bucket()).key(key))).url().toURI();
        } catch (SdkException | URISyntaxException e) {
            throw unavailable(e);
        }
    }

    /** The file's size in bytes, or -1 if nothing is there yet; used to confirm an upload finished. */
    public long sizeOf(String key) {
        checkKey(key);
        try {
            return s3.headObject(head -> head.bucket(bucket()).key(key)).contentLength();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return -1;
            }
            throw unavailable(e);
        } catch (SdkException e) {
            throw unavailable(e);
        }
    }

    /** Saves the file at {@code key} to {@code target}, which must not exist yet. */
    public void download(String key, Path target) {
        checkKey(key);
        try {
            s3.getObject(get -> get.bucket(bucket()).key(key), target);
        } catch (SdkException e) {
            throw unavailable(e);
        }
    }

    public void upload(String key, Path source, String contentType) {
        checkKey(key);
        try {
            s3.putObject(put -> put.bucket(bucket()).key(key).contentType(contentType), RequestBody.fromFile(source));
        } catch (SdkException e) {
            throw unavailable(e);
        }
    }

    public void delete(String key) {
        checkKey(key);
        try {
            s3.deleteObject(delete -> delete.bucket(bucket()).key(key));
        } catch (SdkException e) {
            throw unavailable(e);
        }
    }

    private String bucket() {
        if (!StringUtils.hasText(properties.bucket())) {
            throw new ApplicationException("MEDIA_UNAVAILABLE", "Uploads aren't set up yet. Set HYPEMAN_MEDIA_BUCKET.");
        }
        return properties.bucket();
    }

    private static void checkKey(String key) {
        if (key == null || key.length() > 512 || !VALID_KEY.matcher(key).matches() || key.contains("..")) {
            throw new IllegalArgumentException("Not a valid media key: " + key);
        }
    }

    private static ApplicationException unavailable(Exception e) {
        log.error("S3 request failed", e);
        return new ApplicationException("MEDIA_UNAVAILABLE", "Media storage isn't reachable right now. Please try again.");
    }

    /** @param headers headers the uploader must send with the PUT, exactly as given */
    public record PresignedUpload(URI url, Map<String, String> headers, Instant expiresAt) {
    }
}
