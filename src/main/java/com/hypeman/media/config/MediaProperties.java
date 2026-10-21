package com.hypeman.media.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * @param bucket the S3 bucket all media lives in, from HYPEMAN_MEDIA_BUCKET
 * @param region the bucket's region, from AWS_REGION
 * @param maxUploadSize the largest file a presigned upload link accepts
 * @param accessKeyId with {@code secretAccessKey}, explicit AWS keys (from .env locally); when blank, AWS's default
 *     credential chain is used
 */
@ConfigurationProperties("hypeman.media")
public record MediaProperties(
        String bucket,
        String region,
        Duration uploadUrlTtl,
        Duration downloadUrlTtl,
        DataSize maxUploadSize,
        String accessKeyId,
        String secretAccessKey) {
}
