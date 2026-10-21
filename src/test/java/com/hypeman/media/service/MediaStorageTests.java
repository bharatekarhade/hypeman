package com.hypeman.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hypeman.common.ApiException;
import com.hypeman.media.config.MediaProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Presigning is local signing with no call to AWS, so these run offline with made-up credentials. */
class MediaStorageTests {

    private static final Instant NOW = Instant.parse("2026-10-21T09:00:00Z");
    private static final StaticCredentialsProvider FAKE_CREDENTIALS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"));

    private final S3Presigner presigner = S3Presigner.builder().region(Region.AP_NORTHEAST_1).credentialsProvider(FAKE_CREDENTIALS).build();
    private final S3Client s3 = S3Client.builder().region(Region.AP_NORTHEAST_1).credentialsProvider(FAKE_CREDENTIALS).build();

    @AfterEach
    void close() {
        presigner.close();
        s3.close();
    }

    @Test
    void anUploadLinkPointsAtTheKeyAndSignsTypeAndSize() {
        MediaStorage.PresignedUpload upload = storage("hypeman-media-test")
                .presignUpload("packs/0199f0c8/start/source.mp4", "video/mp4", 4_200_000);

        assertThat(upload.url().getHost()).isEqualTo("hypeman-media-test.s3.ap-northeast-1.amazonaws.com");
        assertThat(upload.url().getPath()).isEqualTo("/packs/0199f0c8/start/source.mp4");
        assertThat(upload.url().getQuery())
                .contains("X-Amz-Expires=900")
                .contains("X-Amz-SignedHeaders=content-length;content-type;host");
        assertThat(upload.headers()).containsEntry("Content-Type", "video/mp4");
        assertThat(upload.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void aDownloadLinkLastsAnHour() {
        URI url = storage("hypeman-media-test").presignDownload("packs/0199f0c8/start/clip.mp4");

        assertThat(url.getPath()).isEqualTo("/packs/0199f0c8/start/clip.mp4");
        assertThat(url.getQuery()).contains("X-Amz-Expires=3600");
    }

    @Test
    void anUploadOverTheLimitIsRefused() {
        assertThatThrownBy(() -> storage("hypeman-media-test").presignUpload("packs/a/start/source.mp4", "video/mp4", DataSize.ofMegabytes(101).toBytes()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo("UPLOAD_TOO_LARGE");
    }

    @Test
    void withoutABucketMediaIsUnavailableRatherThanBroken() {
        assertThatThrownBy(() -> storage("").presignDownload("packs/a/start/clip.mp4"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo("MEDIA_UNAVAILABLE");
    }

    @Test
    void keysThatCouldEscapeTheirFolderAreRejected() {
        MediaStorage storage = storage("hypeman-media-test");
        for (String key : new String[] {"/packs/a", "packs/../users/b", "packs/a b", "", "packs//a"}) {
            assertThatThrownBy(() -> storage.presignDownload(key)).as(key).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private MediaStorage storage(String bucket) {
        MediaProperties properties = new MediaProperties(bucket, "ap-northeast-1", Duration.ofMinutes(15), Duration.ofHours(1), DataSize.ofMegabytes(100), null, null);
        return new MediaStorage(presigner, s3, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
