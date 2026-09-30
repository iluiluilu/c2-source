package com.aismartstore.backend.trash.purge;

import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.SourceSeparationJobRepository;
import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatusRepository;
import com.aismartstore.backend.digitization.results.SegmentRevisionRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.documents.PurgeState;
import com.aismartstore.backend.groups.DocumentGroupShareRepository;
import com.aismartstore.backend.notes.DocumentNoteRepository;
import com.aismartstore.backend.outbox.OutboxEvent;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.tags.DocumentTagRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xóa cứng thùng rác - Quyết định 5 SA (delete-restore-trash-02-xoa-cung-
 * xung-dot.md §2.2/§4.2). {@link #runOnce()} is the entry point invoked by
 * {@link TrashPurgeJob} once the backup gate (§4.1) is open; it loops over
 * lô 200 (§4.2 bước 1) until a batch comes back short (i.e. no more due
 * documents), pausing briefly between lô so the job never monopolizes the
 * connection pool.
 *
 * <p>Bước 1 (claim) is {@link DocumentRepository#claimPurgeBatch}'s own
 * short, implicit transaction. Bước 2-5 for one document (§4.2) all happen
 * inside {@link #purgeOne}, its OWN transaction (via {@code self}, same
 * self-invocation idiom as {@code TrashService}/{@code SystemConfigService})
 * - a failure there rolls back only that document's derivative-delete/bia-
 * mộ writes, never the claim already committed for it (so it correctly
 * stays {@code purging} for {@link PurgeStuckRecoveryJob} to pick up later)
 * and never any other document already purged earlier in the same lô.
 */
@Service
public class TrashPurgeService {

    private static final Logger log = LoggerFactory.getLogger(TrashPurgeService.class);

    /** §4.2 bước 1: lô 200. */
    static final int BATCH_SIZE = 200;
    /**
     * Upper bound on how many lô a single {@code TrashPurgeJob} tick will
     * drain (200 x 100 = 20 000 documents/tick) - a safety cap only, so an
     * unexpectedly huge backlog cannot pin one tick open past the next
     * scheduled run; whatever is left is simply picked up next tick.
     */
    static final int MAX_BATCHES_PER_TICK = 100;

    private final DocumentRepository documentRepository;
    private final DocumentTagRepository documentTagRepository;
    private final DocumentNoteRepository documentNoteRepository;
    private final DocumentGroupShareRepository documentGroupShareRepository;
    private final SegmentRevisionRepository segmentRevisionRepository;
    private final ContentSegmentRepository contentSegmentRepository;
    private final DigitizationRunRepository digitizationRunRepository;
    private final DocumentDigitizationStatusRepository documentDigitizationStatusRepository;
    private final DigitizationSourceRepository digitizationSourceRepository;
    private final SourceSeparationJobRepository sourceSeparationJobRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectStorageService objectStorageService;
    private final AuditService auditService;
    private final long batchPauseMs;

    /**
     * Self-reference so {@link #runOnce} calling {@link #purgeOne} per
     * document actually runs through the {@code @Transactional} proxy (same
     * idiom/rationale as {@code TrashService#self}).
     */
    private TrashPurgeService self;

    public TrashPurgeService(
            DocumentRepository documentRepository,
            DocumentTagRepository documentTagRepository,
            DocumentNoteRepository documentNoteRepository,
            DocumentGroupShareRepository documentGroupShareRepository,
            SegmentRevisionRepository segmentRevisionRepository,
            ContentSegmentRepository contentSegmentRepository,
            DigitizationRunRepository digitizationRunRepository,
            DocumentDigitizationStatusRepository documentDigitizationStatusRepository,
            DigitizationSourceRepository digitizationSourceRepository,
            SourceSeparationJobRepository sourceSeparationJobRepository,
            OutboxEventRepository outboxEventRepository,
            ObjectStorageService objectStorageService,
            AuditService auditService,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.trash.purge-batch-pause-ms:200}")
                    long batchPauseMs) {
        this.documentRepository = documentRepository;
        this.documentTagRepository = documentTagRepository;
        this.documentNoteRepository = documentNoteRepository;
        this.documentGroupShareRepository = documentGroupShareRepository;
        this.segmentRevisionRepository = segmentRevisionRepository;
        this.contentSegmentRepository = contentSegmentRepository;
        this.digitizationRunRepository = digitizationRunRepository;
        this.documentDigitizationStatusRepository = documentDigitizationStatusRepository;
        this.digitizationSourceRepository = digitizationSourceRepository;
        this.sourceSeparationJobRepository = sourceSeparationJobRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectStorageService = objectStorageService;
        this.auditService = auditService;
        this.batchPauseMs = batchPauseMs;
        this.self = this;
    }

    @Autowired
    void setSelf(@Lazy TrashPurgeService self) {
        this.self = self;
    }

    public record PurgeRunSummary(int purged, int errors, int batches) {
    }

    /** Invoked by {@link TrashPurgeJob} once the backup gate (§4.1) is open. */
    public PurgeRunSummary runOnce() {
        int totalPurged = 0;
        int totalErrors = 0;
        int batches = 0;

        while (batches < MAX_BATCHES_PER_TICK) {
            List<UUID> batchIds = documentRepository.claimPurgeBatch(BATCH_SIZE);
            if (batchIds.isEmpty()) {
                break;
            }
            batches++;

            for (UUID id : batchIds) {
                try {
                    self.purgeOne(id);
                    totalPurged++;
                } catch (Exception e) {
                    totalErrors++;
                    log.error(
                            "trash-purge: failed to purge document {} - left in 'purging' for "
                                    + "PurgeStuckRecoveryJob to reclaim",
                            id,
                            e);
                }
            }

            if (batchIds.size() < BATCH_SIZE) {
                break; // last (partial) lô - no more due documents right now.
            }
            pauseBetweenBatches();
        }

        log.info("trash-purge: purged={} errors={} batches={}", totalPurged, totalErrors, batches);
        return new PurgeRunSummary(totalPurged, totalErrors, batches);
    }

    /**
     * §4.2 bước 2-5 for exactly one document, already claimed (flipped to
     * {@code purging}) by {@link #runOnce}. Idempotent by construction - see
     * class Javadoc and §4.3 - so re-running this on a document a previous,
     * crashed attempt only partially processed is always safe.
     */
    @Transactional
    public void purgeOne(UUID documentId) {
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            log.warn("trash-purge: document {} vanished mid-purge (unexpected, treating as done)", documentId);
            return;
        }
        if (PurgeState.PURGED.equals(document.getPurgeState())) {
            return; // already purged by a previous, resumed attempt - nothing left to do.
        }

        // §4.2 bước 2: xóa dữ liệu phái sinh.
        documentTagRepository.deleteByDocumentId(documentId);
        documentNoteRepository.deleteByDocumentId(documentId);
        documentGroupShareRepository.deleteByDocumentId(documentId);
        // bugs/bug-004-trash-purge-missing-child-table-cleanup.md - thu tu bat
        // buoc theo chuoi FK ON DELETE NO ACTION that: cat dependsOnRunId (tu
        // tham chieu giua cac run) -> segment_revision -> content_segment ->
        // digitization_run, TRUOC KHI xoa digitization_source (thu ma
        // digitization_run.source_id tro toi).
        digitizationRunRepository.clearDependsOnRunIdByDocumentId(documentId);
        segmentRevisionRepository.deleteByContentSegmentDocumentId(documentId);
        contentSegmentRepository.deleteByDocumentId(documentId);
        digitizationRunRepository.deleteByDocumentId(documentId);
        digitizationSourceRepository.deleteByDocumentId(documentId);
        sourceSeparationJobRepository.deleteByDocumentId(documentId);
        // bugs/bug-004-trash-purge-missing-child-table-cleanup.md - khong co FK
        // phu thuoc nao, nhung truoc day bi bo sot hoan toan: hang cache trang
        // thai so hoa nay khong bao gio duoc don, tao drift vinh vien sau khi
        // purge (da xac nhan tren stg).
        documentDigitizationStatusRepository.deleteByDocumentId(documentId);
        writePurgedOutboxEvent(documentId);

        // §4.2 bước 3: xóa object SeaweedFS, trừ khi còn document khác (chưa
        // purge) dùng chung sha256 (dedup, data-ingestion.md).
        String storageKey = document.getStorageKey();
        String sha256 = document.getSha256();
        if (storageKey != null
                && sha256 != null
                && !documentRepository.existsBySha256AndIdNotAndStatusNot(sha256, documentId, DocumentStatus.PURGED)) {
            objectStorageService.purgeWarehouseObject(storageKey);
        }

        // §4.2 bước 4: rỗng hóa + bia mộ (§2.2). id/sha256/original_name/
        // deleted_at giữ nguyên - captured below for the audit entry BEFORE
        // any further mutation (none of these are touched here, but the
        // intent is to read them once, close to the source of truth).
        String originalNameForAudit = document.getOriginalName();
        String sha256ForAudit = sha256 == null ? "" : sha256;

        document.setPurgeState(PurgeState.PURGED);
        document.setPurgedAt(OffsetDateTime.now());
        document.setPurgingStartedAt(null);
        document.setStatus(DocumentStatus.PURGED);
        document.setMimeType("");
        document.setUploaderSub("");
        document.setUploaderUsername("");
        document.setSize(0);
        document.setStorageKey(null);
        document.setScanResult(null);
        // relative_path is NOT NULL (unlike storage_key/scan_result) - '' instead of null.
        document.setRelativePath("");
        document.setFolderId(null);
        document.setDeletedBySub(null);
        document.setDeletedByUsername(null);
        document.setDeletedReason(null);
        document.setPurgeAfter(null);
        document.setTrashBatchId(null);
        document.setDeletedFolderPathIds(null);
        documentRepository.save(document);

        // §4.2 bước 5: audit - never touches document.trash/document.restore rows (Quyết định 8 SA).
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sha256", sha256ForAudit);
        metadata.put("originalName", originalNameForAudit);
        auditService.record(new AuditService.AuditEntry(
                "system", null, "document.purge", "document", documentId.toString(), "success", null, null, null,
                null, metadata));
    }

    private void writePurgedOutboxEvent(UUID documentId) {
        OffsetDateTime now = OffsetDateTime.now();
        OutboxEvent event = new OutboxEvent();
        event.setEventType("document.purged");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", documentId.toString());
        payload.put("changedAt", now.toString());
        event.setPayload(payload);
        event.setCreatedAt(now);
        outboxEventRepository.save(event);
    }

    private void pauseBetweenBatches() {
        if (batchPauseMs <= 0) {
            return;
        }
        try {
            Thread.sleep(batchPauseMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
