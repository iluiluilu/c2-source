package com.aismartstore.backend.ingest;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import com.aismartstore.backend.warehouse.WarehouseCapacityService;
import com.aismartstore.backend.warehouse.WarehouseService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * data-ingestion.md §4.1/§4.2/§4.4: opens/tracks/lazily-expires upload
 * batches - the server-side source of truth for the two batch-wide ingest
 * limits ({@code ingest.max_files_per_batch}/{@code max_batch_size_bytes}).
 */
@Service
public class UploadBatchService {

    private final UploadBatchRepository uploadBatchRepository;
    private final WarehouseService warehouseService;
    private final WarehouseCapacityService warehouseCapacityService;
    private final SystemConfigService systemConfigService;

    public UploadBatchService(
            UploadBatchRepository uploadBatchRepository,
            WarehouseService warehouseService,
            WarehouseCapacityService warehouseCapacityService,
            SystemConfigService systemConfigService) {
        this.uploadBatchRepository = uploadBatchRepository;
        this.warehouseService = warehouseService;
        this.warehouseCapacityService = warehouseCapacityService;
        this.systemConfigService = systemConfigService;
    }

    /**
     * GET /ingest/limits (Story 2 AC-3a). {@code chunkThresholdBytes} is a
     * chunked-file-upload.md Quyết định 4 addition - the client's pre-check
     * uses it to decide whole-file vs chunked upload (AC-1/AC-2), unrelated to
     * the 3 original batch-wide limits above it.
     */
    public record Limits(
            long maxFilesPerBatch, long maxBatchSizeBytes, long maxFileSizeBytes, long chunkThresholdBytes) {
    }

    public Limits getLimits() {
        return new Limits(
                systemConfigService.getLong(SystemConfigDefaults.INGEST_MAX_FILES_PER_BATCH),
                systemConfigService.getLong(SystemConfigDefaults.INGEST_MAX_BATCH_SIZE_BYTES),
                systemConfigService.getLong(SystemConfigDefaults.INGEST_MAX_FILE_SIZE_BYTES),
                systemConfigService.getLong(SystemConfigDefaults.UPLOAD_CHUNK_THRESHOLD_BYTES));
    }

    /** §4.1: validate folder + declared limits + warehouse capacity, then create the batch. */
    @Transactional
    public UploadBatch open(KeycloakUser user, UUID targetFolderId, int declaredFileCount, long declaredTotalBytes) {
        if (!warehouseService.folderExists(targetFolderId)) {
            throw new NotFoundException("Không tìm thấy thư mục");
        }

        Limits limits = getLimits();
        if (declaredFileCount > limits.maxFilesPerBatch() || declaredTotalBytes > limits.maxBatchSizeBytes()) {
            throw batchLimitExceeded();
        }

        // Pre-check (Story 5 AC-5a): reject opening new work once the warehouse is BLOCKED.
        warehouseCapacityService.assertIngestAllowed();

        UploadBatch batch = new UploadBatch();
        batch.setTargetFolderId(targetFolderId);
        batch.setOwnerSub(user.sub());
        batch.setOwnerUsername(user.preferredUsername());
        batch.setDeclaredFileCount(declaredFileCount);
        batch.setDeclaredTotalBytes(declaredTotalBytes);
        batch.setStatus(UploadBatchStatus.OPEN);
        OffsetDateTime now = OffsetDateTime.now();
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        batch.setExpiresAt(now.plusHours(24));
        return uploadBatchRepository.save(batch);
    }

    /**
     * §4.2 step 1: load a batch for receiving a file, lazily marking it
     * EXPIRED if its window has passed. Throws for every non-OPEN outcome -
     * 404 if the batch does not exist, 409 BATCH_EXPIRED / BATCH_CLOSED
     * otherwise.
     */
    @Transactional
    public UploadBatch loadOpenBatch(UUID batchId) {
        UploadBatch batch = find(batchId);

        if (UploadBatchStatus.OPEN.equals(batch.getStatus()) && OffsetDateTime.now().isAfter(batch.getExpiresAt())) {
            batch.setStatus(UploadBatchStatus.EXPIRED);
            batch.setUpdatedAt(OffsetDateTime.now());
            uploadBatchRepository.save(batch);
        }

        if (UploadBatchStatus.OPEN.equals(batch.getStatus())) {
            return batch;
        }
        if (UploadBatchStatus.EXPIRED.equals(batch.getStatus())) {
            throw batchExpired();
        }
        throw batchClosed();
    }

    /**
     * §4.2 step 4: atomic reserve. On failure marks the batch LIMIT_EXCEEDED
     * (idempotent) and throws 409 BATCH_LIMIT_EXCEEDED - files already
     * accepted are untouched either way.
     */
    @Transactional
    public void reserve(UUID batchId, long fileSize) {
        Limits limits = getLimits();
        int updated =
                uploadBatchRepository.reserve(batchId, fileSize, limits.maxFilesPerBatch(), limits.maxBatchSizeBytes());
        if (updated == 0) {
            uploadBatchRepository.updateStatus(batchId, UploadBatchStatus.LIMIT_EXCEEDED);
            throw batchLimitExceeded();
        }
    }

    @Transactional
    public void release(UUID batchId, long fileSize) {
        uploadBatchRepository.release(batchId, fileSize);
    }

    @Transactional
    public void incrementAccepted(UUID batchId) {
        uploadBatchRepository.incrementAccepted(batchId);
    }

    @Transactional
    public void incrementSkipped(UUID batchId) {
        uploadBatchRepository.incrementSkipped(batchId);
    }

    @Transactional
    public void incrementRejected(UUID batchId) {
        uploadBatchRepository.incrementRejected(batchId);
    }

    /** POST /ingest/batches/{id}/close - optional convenience, never required (§3). */
    @Transactional
    public UploadBatch close(UUID batchId) {
        UploadBatch batch = find(batchId);
        if (UploadBatchStatus.OPEN.equals(batch.getStatus())) {
            batch.setStatus(UploadBatchStatus.CLOSED);
            batch.setUpdatedAt(OffsetDateTime.now());
            uploadBatchRepository.save(batch);
        }
        return batch;
    }

    /** GET /ingest/batches/{id} - reflects lazy expiry without throwing (Story 2 AC-3). */
    @Transactional
    public UploadBatch getStatus(UUID batchId) {
        UploadBatch batch = find(batchId);
        if (UploadBatchStatus.OPEN.equals(batch.getStatus()) && OffsetDateTime.now().isAfter(batch.getExpiresAt())) {
            batch.setStatus(UploadBatchStatus.EXPIRED);
            batch.setUpdatedAt(OffsetDateTime.now());
            uploadBatchRepository.save(batch);
        }
        return batch;
    }

    private UploadBatch find(UUID batchId) {
        return uploadBatchRepository.findById(batchId).orElseThrow(UploadBatchService::batchNotFound);
    }

    // ---- error bodies (match spec §3 API contract) -----------------------

    private static ApiException batchLimitExceeded() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "BATCH_LIMIT_EXCEEDED");
        body.put("message", "Lượt tải vượt quá hạn mức cho phép");
        return new ApiException(HttpStatus.CONFLICT, body, "Batch limit exceeded");
    }

    private static ApiException batchClosed() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "BATCH_CLOSED");
        body.put("message", "Lượt tải đã đóng");
        return new ApiException(HttpStatus.CONFLICT, body, "Batch closed");
    }

    private static ApiException batchExpired() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "BATCH_EXPIRED");
        body.put("message", "Lượt tải đã hết hạn");
        return new ApiException(HttpStatus.CONFLICT, body, "Batch expired");
    }

    private static ApiException batchNotFound() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "BATCH_NOT_FOUND");
        body.put("message", "Không tìm thấy lượt tải");
        return new ApiException(HttpStatus.NOT_FOUND, body, "Không tìm thấy lượt tải");
    }
}
