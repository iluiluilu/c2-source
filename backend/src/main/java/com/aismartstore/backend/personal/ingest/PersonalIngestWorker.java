package com.aismartstore.backend.personal.ingest;

import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.config.StorageProperties;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentsService;
import com.aismartstore.backend.documents.IngestOutcome;
import com.aismartstore.backend.documents.IngestSource;
import com.aismartstore.backend.ingest.UploadBatch;
import com.aismartstore.backend.ingest.UploadBatchService;
import com.aismartstore.backend.personal.PersonalFile;
import com.aismartstore.backend.personal.PersonalFileRepository;
import com.aismartstore.backend.storage.ObjectStorageService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * personal-to-warehouse.md §4 step 2: server-side, sequential processing of
 * one run. Separate bean so {@link Async} is honoured (no self-invocation).
 * Every item is wrapped in try/catch: one failure never stops the run (AC-15).
 * Deliberately not {@code @Transactional} - the ingest core relies on each
 * repository call committing on its own.
 */
@Component
public class PersonalIngestWorker {

    private static final Logger log = LoggerFactory.getLogger(PersonalIngestWorker.class);

    private final PersonalIngestItemRepository itemRepository;
    private final PersonalFileRepository personalFileRepository;
    private final UploadBatchService uploadBatchService;
    private final DocumentsService documentsService;
    private final ObjectStorageService objectStorageService;
    private final StorageProperties storageProperties;
    private final AuditService auditService;

    public PersonalIngestWorker(
            PersonalIngestItemRepository itemRepository,
            PersonalFileRepository personalFileRepository,
            UploadBatchService uploadBatchService,
            DocumentsService documentsService,
            ObjectStorageService objectStorageService,
            StorageProperties storageProperties,
            AuditService auditService) {
        this.itemRepository = itemRepository;
        this.personalFileRepository = personalFileRepository;
        this.uploadBatchService = uploadBatchService;
        this.documentsService = documentsService;
        this.objectStorageService = objectStorageService;
        this.storageProperties = storageProperties;
        this.auditService = auditService;
    }

    @Async(PersonalIngestExecutorConfig.EXECUTOR_NAME)
    public void run(KeycloakUser user, UUID batchId, UUID targetFolderId, String note) {
        try {
            runSync(user, batchId, targetFolderId, note);
        } catch (RuntimeException e) {
            log.error("personal-ingest run for batch {} aborted unexpectedly", batchId, e);
        }
    }

    /** Synchronous body of {@link #run}; package-visible/public for tests. */
    public void runSync(KeycloakUser user, UUID batchId, UUID targetFolderId, String note) {
        try {
            List<PersonalIngestItem> items = itemRepository.findByBatchIdOrderByOriginalNameAsc(batchId).stream()
                    .filter(i -> PersonalIngestItem.PENDING.equals(i.getStatus()))
                    .toList();
            for (PersonalIngestItem item : items) {
                try {
                    processItem(user, batchId, targetFolderId, note, item);
                } catch (RuntimeException e) {
                    log.error("personal-ingest item {} failed unexpectedly", item.getId(), e);
                    finish(user, targetFolderId, item, PersonalIngestItem.FAILED, "INTERNAL_ERROR", null, null, 0);
                }
            }
        } finally {
            try {
                uploadBatchService.close(batchId);
            } catch (RuntimeException e) {
                log.error("Failed to close personal-ingest batch {}", batchId, e);
            }
        }
    }

    private void processItem(
            KeycloakUser user, UUID batchId, UUID targetFolderId, String note, PersonalIngestItem item) {
        UploadBatch batch;
        try {
            batch = uploadBatchService.loadOpenBatch(batchId);
        } catch (ApiException e) {
            finish(user, targetFolderId, item, PersonalIngestItem.FAILED, codeOf(e), null, null, item.getSize());
            return;
        }

        PersonalFile file = personalFileRepository
                .findByIdAndOwnerSub(item.getPersonalFileId(), user.sub())
                .orElse(null);
        if (file == null) {
            uploadBatchService.incrementRejected(batchId);
            finish(user, targetFolderId, item, PersonalIngestItem.FAILED, "SOURCE_NOT_FOUND", null, null, item.getSize());
            return;
        }

        String personalBucket = storageProperties.personalBucket();
        IngestSource source = new IngestSource(
                file.getOriginalName(),
                file.getMimeType(),
                file.getSize(),
                () -> objectStorageService.getObject(file.getStorageKey(), personalBucket),
                item.getRelativePath(),
                file.getSha256(),
                file.getId());

        try {
            IngestOutcome outcome = documentsService.ingestCore(user, batch, source, note);
            if (outcome instanceof IngestOutcome.Skipped skipped) {
                finish(user, targetFolderId, item, PersonalIngestItem.SKIPPED, skipped.reason(),
                        skipped.documentId(), null, file.getSize());
            } else {
                IngestOutcome.Ingested ingested = (IngestOutcome.Ingested) outcome;
                UUID[] dups = ingested.duplicateOf().stream().map(Document::getId).toArray(UUID[]::new);
                finish(user, targetFolderId, item, PersonalIngestItem.INGESTED, null,
                        ingested.document().getId(), dups, file.getSize());
            }
        } catch (ApiException e) {
            finish(user, targetFolderId, item, PersonalIngestItem.FAILED, codeOf(e), null, null, file.getSize());
        } catch (NoSuchKeyException e) {
            // personal file row exists but its object vanished (deleted mid-run / orphan).
            uploadBatchService.incrementRejected(batchId);
            finish(user, targetFolderId, item, PersonalIngestItem.FAILED, "SOURCE_NOT_FOUND", null, null, file.getSize());
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof NoSuchKeyException) {
                uploadBatchService.incrementRejected(batchId);
                finish(user, targetFolderId, item, PersonalIngestItem.FAILED, "SOURCE_NOT_FOUND", null, null,
                        file.getSize());
            } else {
                throw e;
            }
        }
    }

    @SuppressWarnings("unchecked")
    static String codeOf(ApiException e) {
        if (e.getBody() instanceof Map<?, ?> body && body.get("code") != null) {
            return String.valueOf(((Map<String, Object>) body).get("code"));
        }
        return "INTERNAL_ERROR";
    }

    private void finish(
            KeycloakUser user,
            UUID targetFolderId,
            PersonalIngestItem item,
            String status,
            String code,
            UUID documentId,
            UUID[] duplicateOf,
            long size) {
        item.setStatus(status);
        item.setCode(code);
        item.setDocumentId(documentId);
        item.setDuplicateOf(duplicateOf);
        item.setProcessedAt(OffsetDateTime.now());
        itemRepository.save(item);

        // AC-23: never includes personal folder names/paths - only ids, file name, size, target.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourcePersonalFileId", item.getPersonalFileId().toString());
        metadata.put("originalName", item.getOriginalName());
        metadata.put("size", item.getSize());
        metadata.put("targetFolderId", targetFolderId.toString());
        metadata.put("result", status);
        if (code != null) {
            metadata.put("code", code);
        }
        if (documentId != null) {
            metadata.put("documentId", documentId.toString());
        }
        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "ingest.from_personal.file", "personal_file",
                item.getPersonalFileId().toString(),
                PersonalIngestItem.FAILED.equals(status) ? "failure" : "success",
                null, null, null, null, metadata));
    }
}
