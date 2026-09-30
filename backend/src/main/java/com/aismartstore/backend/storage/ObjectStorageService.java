package com.aismartstore.backend.storage;

import com.aismartstore.backend.config.StorageProperties;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Object storage behind SeaweedFS's S3-compatible API. Path-style access is
 * required - SeaweedFS does not serve virtual-host-style bucket URLs. Java
 * port of the NestJS backend's storage/object-storage.service.ts.
 *
 * Two buckets, deliberately separate:
 * - the warehouse bucket (default for put/get) only ever receives fresh keys
 *   and is never deleted from (NFR-09 immutable originals; keeps it
 *   WORM-able for later hardening).
 * - the personal bucket (FR-14 personal files) supports hard delete.
 */
@Service
public class ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageService.class);

    private final StorageProperties properties;
    private final S3Client client;

    public ObjectStorageService(StorageProperties properties) {
        this.properties = properties;
        this.client = S3Client.builder()
                .endpointOverride(URI.create(properties.s3Endpoint()))
                .region(Region.of(properties.s3Region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.s3AccessKeyId(), properties.s3SecretAccessKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    /**
     * The compose healthcheck only proves the SeaweedFS master is up, not
     * that the S3 gateway is accepting requests yet - so retry bucket
     * creation for a while instead of failing the boot.
     */
    @PostConstruct
    public void ensureBuckets() {
        ensureBucket(properties.bucket());
        ensureBucket(properties.personalBucket());
        ensureBucket(properties.auditArchiveBucket());
    }

    private void ensureBucket(String bucket) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            try {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("Bucket '{}' ready", bucket);
                return;
            } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
                log.info("Bucket '{}' ready", bucket);
                return;
            } catch (Exception e) {
                if (System.currentTimeMillis() >= deadline) {
                    // Don't crash the app: uploads will fail loudly, everything else works.
                    log.error("Could not ensure bucket '{}': {}", bucket, e.toString());
                    return;
                }
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public void putObject(String key, File file, String contentType) {
        putObject(key, file, contentType, properties.bucket());
    }

    public void putObject(String key, File file, String contentType, String bucket) {
        client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                RequestBody.fromFile(file));
    }

    public ResponseInputStream<GetObjectResponse> getObject(String key) {
        return getObject(key, properties.bucket());
    }

    public ResponseInputStream<GetObjectResponse> getObject(String key, String bucket) {
        return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    }

    /**
     * Hard object removal - personal files only. Never call this against the
     * warehouse bucket: originals there are immutable (NFR-09).
     */
    public void deleteObject(String key) {
        deleteObject(key, properties.personalBucket());
    }

    public void deleteObject(String key, String bucket) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    /**
     * delete-restore-trash-02-xoa-cung-xung-dot.md §4.2 bước 3 / Quyết định
     * 5 SA: the ONE documented, audited exception to "the warehouse bucket
     * is immutable" (see {@link #deleteObject(String)}'s javadoc) - reachable
     * only from {@code TrashPurgeService}, and only after that service's own
     * sha256 dedup check (no other {@code document} row still references
     * this object). Never expose this through a controller.
     */
    public void purgeWarehouseObject(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            deleteObject(key, properties.bucket());
            log.info("trash-purge: purged warehouse object key='{}'", key);
        } catch (NoSuchKeyException e) {
            // AC-2 (delete-restore-trash-02 §4.2 bước 3): already gone (e.g. a
            // resumed purge after a crash) counts as a successful purge, not
            // an error - idempotent.
            log.info("trash-purge: warehouse object key='{}' already absent (idempotent)", key);
        }
    }
}
