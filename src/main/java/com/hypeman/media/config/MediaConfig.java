package com.hypeman.media.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 access for the backend, as the hypeman-backend IAM user. Its keys come from Spring config when set (.env in
 * development), else from AWS's default chain (real environment variables or an AWS profile). They are only used when
 * a link is signed or S3 is called, so the app starts without them.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaProperties.class)
class MediaConfig {

    @Bean
    AwsCredentialsProvider awsCredentialsProvider(MediaProperties properties) {
        if (StringUtils.hasText(properties.accessKeyId()) && StringUtils.hasText(properties.secretAccessKey())) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }

    @Bean
    S3Presigner s3Presigner(MediaProperties properties, AwsCredentialsProvider credentials) {
        return S3Presigner.builder().region(Region.of(properties.region())).credentialsProvider(credentials).build();
    }

    @Bean
    S3Client s3Client(MediaProperties properties, AwsCredentialsProvider credentials) {
        return S3Client.builder().region(Region.of(properties.region())).credentialsProvider(credentials).build();
    }
}
