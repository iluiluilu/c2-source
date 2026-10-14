package com.aismartstore.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds the {@code app.storage.*} keys in application.yml. */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String s3Endpoint,
        String s3Region,
        String s3AccessKeyId,
        String s3SecretAccessKey,
        String bucket,
        String personalBucket,
        String auditArchiveBucket,
        String reportsBucket) {
}
