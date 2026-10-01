package com.aismartstore.backend.documents;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.common.SqlErrors;
import com.aismartstore.backend.digitization.DocumentIngestedEvent;
import com.aismartstore.backend.displayname.DisplayNameValidator;
import com.aismartstore.backend.displayname.NameCheckRepository;
import com.aismartstore.backend.groups.BusinessDataScope;
import com.aismartstore.backend.groups.DocumentScopeGuard;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.ingest.UploadBatch;
import com.aismartstore.backend.ingest.UploadBatchService;
import com.aismartstore.backend.notes.NotesService;
import com.aismartstore.backend.outbox.OutboxEvent;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.scanning.AllowlistService;
import com.aismartstore.backend.scanning.ClamdService;
import com.aismartstore.backend.scanning.ScannerUnavailableException;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import com.aismartstore.backend.tags.DocTypeAssignmentService;
import com.aismartstore.backend.tags.DocumentTag;
import com.aismartstore.backend.tags.DocumentTagRepository;
import com.aismartstore.backend.tags.Tag;
import com.aismartstore.backend.tags.TagRepository;
import com.aismartstore.backend.warehouse.WarehouseCapacityService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * Java port of the NestJS backend's documents/documents.service.ts ingest
 * pipeline, rewritten per data-ingestion.md §4.2 into the 2-step batch flow
 * (Task 5): {@link #ingestBatchFile} replaces the old single-shot
 * {@code ingest()} - format check now runs before hashing (Story 3 AC-1), a
 * per-file size cap is enforced before touching the batch counters, and the
 * SeaweedFS write is bracketed by a {@code pending} row so a crash leaves a
 * row a SQL query can find instead of an orphan object (Quyet dinh 2 muc 0).
 */
@Service
public class DocumentsService {

    private static final Logger log = LoggerFactory.getLogger(DocumentsService.class);

    private final DocumentRepository documentRepository;
    private final UploadBatchService uploadBatchService;
    private final WarehouseCapacityService warehouseCapacityService;
    private final AllowlistService allowlistService;
    private final SystemConfigService systemConfigService;
    private final ClamdService clamdService;
    private final ObjectStorageService objectStorageService;
    private final NotesService notesService;
    private final DocumentTagRepository documentTagRepository;
    private final TagRepository tagRepository;
    private final DocTypeAssignmentService docTypeAssignmentService;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;
    private final GroupMemberRepository groupMemberRepository;
    private final DocumentScopeGuard documentScopeGuard;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final OutboxEventRepository outboxEventRepository;
    private final NameCheckRepository nameCheckRepository;

    public DocumentsService(
            DocumentRepository documentRepository,
            UploadBatchService uploadBatchService,
            WarehouseCapacityService warehouseCapacityService,
            AllowlistService allowlistService,
            SystemConfigService systemConfigService,
            ClamdService clamdService,
            ObjectStorageService objectStorageService,
            NotesService notesService,
            DocumentTagRepository documentTagRepository,
            TagRepository tagRepository,
            DocTypeAssignmentService docTypeAssignmentService,
            AuditService auditService,
            AuditRequestContext auditRequestContext,
            GroupMemberRepository groupMemberRepository,
            DocumentScopeGuard documentScopeGuard,
            ApplicationEventPublisher applicationEventPublisher,
            OutboxEventRepository outboxEventRepository,
            NameCheckRepository nameCheckRepository) {
        this.documentRepository = documentRepository;
        this.uploadBatchService = uploadBatchService;
        this.warehouseCapacityService = warehouseCapacityService;
        this.allowlistService = allowlistService;
        this.systemConfigService = systemConfigService;
        this.clamdService = clamdService;
        this.objectStorageService = objectStorageService;
        this.notesService = notesService;
        this.documentTagRepository = documentTagRepository;
        this.tagRepository = tagRepository;
        this.docTypeAssignmentService = docTypeAssignmentService;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
        this.groupMemberRepository = groupMemberRepository;
        this.documentScopeGuard = documentScopeGuard;
        this.applicationEventPublisher = applicationEventPublisher;
        this.outboxEventRepository = outboxEventRepository;
        this.nameCheckRepository = nameCheckRepository;
    }

    public record DocumentContent(Document document, ResponseInputStream<GetObjectResponse> stream) {
    }

    /**
     * Flat row for GET /documents (data-ingestion.md §3: response shape is
     * unchanged from the old NestJS backend's {@code Document & {tags}} -
     * fields spread alongside a batch-loaded {@code tags} list, not nested
     * under a {@code document} key). Frontend's {@code DocumentRow} type
     * relies on this being flat.
     */
    public record DocumentListItem(
            UUID id,
            String originalName,
            String displayName,
            String mimeType,
            long size,
            String sha256,
            String relativePath,
            UUID folderId,
            String uploaderUsername,
            String status,
            OffsetDateTime createdAt,
            List<Tag> tags) {

        static DocumentListItem of(Document document, List<Tag> tags) {
            return new DocumentListItem(
                    document.getId(),
                    document.getOriginalName(),
                    document.getDisplayName(),
                    document.getMimeType(),
                    document.getSize(),
                    document.getSha256(),
                    document.getRelativePath(),
                    document.getFolderId(),
                    document.getUploaderUsername(),
                    document.getStatus(),
                    document.getCreatedAt(),
                    tags);
        }
    }

    /**
     * data-ingestion.md §4.2: receive one file into an already-OPEN batch.
     * Steps are numbered to match the spec exactly - do not reorder without
     * updating both.
     */
    public IngestOutcome ingestBatchFile(
            KeycloakUser user, UUID batchId, MultipartFile file, String relativePath, String displayName,
            String note) {
        // 1) load + lazily expire the batch.
        UploadBatch batch = uploadBatchService.loadOpenBatch(batchId);

        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unnamed";
        String mimeType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        IngestSource source = new IngestSource(
                originalName, mimeType, file.getSize(), file::getInputStream,
                relativePath == null ? "" : relativePath, null, null, displayName);
        return ingestCore(user, batch, source, note);
    }

    /**
     * personal-to-warehouse.md §1: steps 2-10 of data-ingestion.md §4.2, shared
     * by the multipart channel ({@link #ingestBatchFile}) and the
     * personal-to-warehouse channel. Runs with no surrounding transaction on
     * purpose (pending row must commit before putObject).
     */
    public IngestOutcome ingestCore(KeycloakUser user, UploadBatch batch, IngestSource src, String note) {
        UUID batchId = batch.getId();
        boolean fromPersonal = src.sourcePersonalFileId() != null;

        // 2) pre-check warehouse capacity again, per file (it may have crossed
        // the block threshold since the batch was opened).
        warehouseCapacityService.assertIngestAllowed();

        String originalName = src.originalName();
        String path = DisplayNameValidator.normalizeRelativePath(src.relativePath());
        // 3) display-name validation (QD4, AC-28) then a cheap early conflict check - before any quota
        // reservation/scan. The unique index below stays the authoritative arbiter (race between 2 uploads).
        String displayName = DisplayNameValidator.validate(originalName, src.displayName());
        if (nameCheckRepository
                .warehouseTaken(batch.getTargetFolderId(), List.of(new NameCheckRepository.Candidate(path, displayName)))[0]) {
            log.info("display_name conflict (early) context=ingest");
            throw DisplayNameValidator.conflict();
        }

        // 3b) per-file size cap - before reserving batch quota, on purpose.
        long maxFileSizeBytes = systemConfigService.getLong(SystemConfigDefaults.INGEST_MAX_FILE_SIZE_BYTES);
        if (src.size() > maxFileSizeBytes) {
            uploadBatchService.incrementRejected(batchId);
            throw fileTooLarge(maxFileSizeBytes);
        }

        // 4) reserve batch quota atomically (throws 409 BATCH_LIMIT_EXCEEDED on failure).
        uploadBatchService.reserve(batchId, src.size());

        String mimeType = src.mimeType();

        Path tempFile = bufferToTempFile(src.open());
        try {
            // personal-to-warehouse.md AC-13: verify the streamed bytes against the
            // checksum recorded when the personal file was stored, before anything else.
            String verifiedSha256 = null;
            if (src.expectedSha256() != null) {
                verifiedSha256 = sha256Hex(tempFile);
                if (!verifiedSha256.equalsIgnoreCase(src.expectedSha256())) {
                    uploadBatchService.incrementRejected(batchId);
                    throw checksumMismatch();
                }
            }

            // 5) format check - reject before ever hashing/scanning (Story 3 AC-1/AC-2).
            AllowlistService.FormatCheck formatCheck =
                    allowlistService.checkFormat(tempFile.toFile(), originalName, mimeType);
            if (!formatCheck.ok()) {
                persistFailure(
                        user, batch.getTargetFolderId(), path, originalName, displayName, mimeType, tempFile, null,
                        DocumentStatus.REJECTED, Map.of("engine", "clamav", "rejectReason", formatCheck.reason()));
                uploadBatchService.incrementRejected(batchId);
                throw invalidFormat(formatCheck.reason());
            }

            // 6) SHA-256.
            String sha256 = verifiedSha256 != null ? verifiedSha256 : sha256Hex(tempFile);

            // 7) ClamAV - scanStream() itself fails closed (ScannerUnavailableException)
            // if the scanner is down/times out; no document row is created for that case.
            ClamdService.ScanVerdict verdict = scan(tempFile);
            if (!verdict.clean()) {
                persistFailure(
                        user, batch.getTargetFolderId(), path, originalName, displayName, mimeType, tempFile, sha256,
                        DocumentStatus.INFECTED, Map.of("engine", "clamav", "signature", verdict.signature()));
                uploadBatchService.incrementRejected(batchId);
                throw malwareDetected(verdict.signature());
            }

            List<Document> duplicatesBySha = documentRepository.findBySha256AndStatus(sha256, DocumentStatus.CLEAN);

            // 8) save: pending row (committed) -> putObject -> clean (Quyet dinh 2 muc 0).
            String storageKey = "documents/" + UUID.randomUUID();

            Document document = new Document();
            document.setOriginalName(originalName);
            document.setDisplayName(displayName);
            document.setMimeType(mimeType);
            document.setSize(fileSize(tempFile));
            document.setSha256(sha256);
            document.setStorageKey(storageKey);
            document.setRelativePath(path);
            document.setFolderId(batch.getTargetFolderId());
            document.setUploaderSub(user.sub());
            document.setUploaderUsername(user.preferredUsername());
            document.setStatus(DocumentStatus.PENDING);
            if (fromPersonal) {
                document.setSourceKind("personal");
                document.setSourcePersonalFileId(src.sourcePersonalFileId());
            }
            document.setCreatedAt(OffsetDateTime.now());
            try {
                documentRepository.save(document); // 8a - commits immediately (no surrounding @Transactional).
            } catch (DataIntegrityViolationException e) {
                if (SqlErrors.isUniqueViolation(e)) {
                    // Lost the race on UQ_document_display_name_alive: give the reservation back so the
                    // client's retry (new suffix) does not count this file twice against the batch.
                    uploadBatchService.release(batchId, src.size());
                    log.info("display_name conflict (unique) context=ingest");
                    throw DisplayNameValidator.conflict();
                }
                throw e;
            }

            try {
                objectStorageService.putObject(storageKey, tempFile.toFile(), mimeType); // 8b
            } catch (RuntimeException e) {
                log.error("putObject failed for pending document {} (storageKey={})", document.getId(), storageKey, e);
                try {
                    documentRepository.deleteById(document.getId());
                } catch (RuntimeException cleanupFailure) {
                    log.error(
                            "Failed to clean up pending document {} after a putObject failure - it is a "
                                    + "harmless orphan row, findable by SQL (status='pending')",
                            document.getId(), cleanupFailure);
                }
                throw storageWriteFailed(e);
            }

            document.setScanResult(Map.of("engine", "clamav", "scannedAt", Instant.now().toString()));
            document.setStatus(DocumentStatus.CLEAN);
            documentRepository.save(document); // 8c

            // personal-to-warehouse.md decision #2: the personal channel never records
            // relativePath (it embeds personal folder names); multipart keeps it.
            Map<String, Object> createMetadata = new LinkedHashMap<>();
            createMetadata.put("sha256", sha256);
            createMetadata.put("folderId", batch.getTargetFolderId().toString());
            if (!fromPersonal) {
                createMetadata.put("relativePath", path);
            }
            createMetadata.put("size", document.getSize());
            auditService.recordAsync(new AuditService.AuditEntry(
                    user.preferredUsername(),
                    user.sub(),
                    "document.create",
                    "document",
                    document.getId().toString(),
                    "success",
                    auditRequestContext.method(),
                    auditRequestContext.path(),
                    200,
                    auditRequestContext.ip(),
                    createMetadata));

            // 9) post-check - never blocks the write that just happened.
            warehouseCapacityService.recomputeAfterWrite();
            uploadBatchService.incrementAccepted(batchId);

            if (note != null && !note.isBlank()) {
                notesService.createForDocument(user, document.getId(), note.trim());
            }

            // classification-notes-01-tags.md §4.3 step 1: auto-assign
            // doc_type from the MIME type right after the document is
            // persisted - never blocks ingest itself if the MIME is
            // unmapped (US2 AC-5), the document just stays visible via
            // GET /documents/unclassified-doc-type until R1 classifies it.
            docTypeAssignmentService.assignOnIngest(document, user.sub(), user.preferredUsername());

            // search-fulltext-04-dong-bo-chi-muc.md Task 5: a separate outbox_event
            // (`document.ingested`) for search-service's indexer - independent of, and in
            // addition to, the DocumentIngestedEvent published below (that one is an internal
            // Spring ApplicationEvent driving the digitization pipeline dispatch, this one is
            // the search-index sync contract; ingestBatchFile only ever runs for WAREHOUSE
            // batches - personal-storage files are a separate `personal_file` table/path that
            // never reaches this method, so no batch-kind check is needed here).
            writeDocumentIngestedOutboxEvent(document.getId());

            // digitization-pipeline-01-source-dispatch.md §4.1 step 2: published
            // right before returning, i.e. strictly after step 8c's save() (and
            // that save's own transaction) has already completed - see
            // DigitizationDispatchListener's Javadoc for why this event is
            // consumed with a plain @EventListener rather than the spec's
            // originally-stated @TransactionalEventListener(AFTER_COMMIT).
            applicationEventPublisher.publishEvent(
                    new DocumentIngestedEvent(document.getId(), document.getFolderId(), document.getMimeType()));

            // 10)
            return new IngestOutcome.Ingested(document, duplicatesBySha);
        } finally {
            deleteQuietly(tempFile);
        }
    }

    /**
     * search-fulltext-04-dong-bo-chi-muc.md Task 5 - payload is exactly
     * {@code {documentId, changedAt}}, no other metadata: the indexer
     * re-reads current document/tag/folder state from Postgres by {@code
     * documentId} when it consumes this event (same pattern as {@code
     * ClassificationOutbox}/{@code NotesService}'s own outbox writes).
     */
    private void writeDocumentIngestedOutboxEvent(UUID documentId) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType("document.ingested");
        OffsetDateTime changedAt = OffsetDateTime.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", documentId.toString());
        payload.put("changedAt", changedAt.toString());
        event.setPayload(payload);
        event.setCreatedAt(changedAt);
        outboxEventRepository.save(event);
    }

    /** Metadata row persisted as evidence even though bytes are never stored - reject rather than quarantine. */
    private void persistFailure(
            KeycloakUser user,
            UUID folderId,
            String relativePath,
            String originalName,
            String displayName,
            String mimeType,
            Path tempFile,
            String sha256,
            String status,
            Map<String, Object> scanResult) {
        Document document = new Document();
        document.setOriginalName(originalName);
        document.setDisplayName(displayName);
        document.setMimeType(mimeType);
        document.setSize(fileSize(tempFile));
        document.setSha256(sha256);
        document.setStorageKey(null);
        document.setRelativePath(relativePath);
        document.setFolderId(folderId);
        document.setUploaderSub(user.sub());
        document.setUploaderUsername(user.preferredUsername());
        document.setStatus(status);
        document.setScanResult(scanResult);
        document.setCreatedAt(OffsetDateTime.now());
        try {
            documentRepository.save(document);
        } catch (DataIntegrityViolationException e) {
            // Evidence row of an infected file whose name is already held (race) - the rejection itself
            // still goes back to the client; only the evidence row is dropped.
            log.warn("Could not persist evidence row for a {} upload (display_name held): {}", status, e.getMessage());
            return;
        }

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(),
                user.sub(),
                "document.reject",
                "document",
                document.getId().toString(),
                "failure",
                auditRequestContext.method(),
                auditRequestContext.path(),
                null,
                auditRequestContext.ip(),
                scanResult));
    }

    /**
     * Batch-loads each result document's tags (avoids N+1), same as OLD's
     * withTags(). rbac-group-scope.md Task 8/Story 3 AC-1-AC-3: every result
     * row is filtered through {@link BusinessDataScope} - {@code user} only
     * ever sees documents they uploaded or that were shared to a group they
     * belong to.
     */
    public List<DocumentListItem> list(
            KeycloakUser user, UUID folderId, UUID tagId, String q, Integer limit, Integer offset) {
        int clampedLimit = Math.min(Math.max(limit == null ? 50 : limit, 1), 200);
        int clampedOffset = Math.max(offset == null ? 0 : offset, 0);
        String trimmedQ = q == null ? null : q.trim();

        var spec = BusinessDataScope.forDocuments(user.sub(), groupMemberRepository)
                .and(folderId != null ? DocumentSpecifications.hasFolderId(folderId) : null)
                .and(tagId != null ? DocumentSpecifications.hasTagId(tagId) : null)
                .and(trimmedQ != null && !trimmedQ.isEmpty() ? DocumentSpecifications.nameContains(trimmedQ) : null);
        List<Document> documents = documentRepository.search(spec, clampedLimit, clampedOffset);
        if (documents.isEmpty()) {
            return List.of();
        }

        List<UUID> documentIds = documents.stream().map(Document::getId).toList();
        List<DocumentTag> links = documentTagRepository.findByDocumentIdIn(documentIds);
        Map<UUID, Tag> tagsById = tagRepository
                .findAllById(links.stream().map(DocumentTag::getTagId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Tag::getId, t -> t));

        Map<UUID, List<Tag>> tagsByDocumentId = new HashMap<>();
        for (DocumentTag link : links) {
            tagsByDocumentId
                    .computeIfAbsent(link.getDocumentId(), k -> new ArrayList<>())
                    .add(tagsById.get(link.getTagId()));
        }

        return documents.stream()
                .map(d -> DocumentListItem.of(d, tagsByDocumentId.getOrDefault(d.getId(), List.of())))
                .toList();
    }

    /**
     * classification-notes-01-tags.md §3 ({@code GET
     * /documents/unclassified-doc-type}, US2 AC-5/AC-6): documents (new
     * ingests and backfilled ones alike, §4.4 step 2 - one shared query) with
     * no {@code doc_type} tag because their MIME type couldn't be mapped, for
     * R1 to classify manually. Scoped via {@link BusinessDataScope}, same as
     * {@link #list}.
     */
    public List<DocumentListItem> listUnclassifiedDocType(KeycloakUser user, Integer limit, Integer offset) {
        int clampedLimit = Math.min(Math.max(limit == null ? 50 : limit, 1), 200);
        int clampedOffset = Math.max(offset == null ? 0 : offset, 0);

        var spec = BusinessDataScope.forDocuments(user.sub(), groupMemberRepository)
                .and(DocumentSpecifications.missingDocType());
        List<Document> documents = documentRepository.search(spec, clampedLimit, clampedOffset);
        return documents.stream().map(d -> DocumentListItem.of(d, List.of())).toList();
    }

    /** rbac-group-scope.md Task 8/Story 3 AC-1-AC-3: scoped to {@code user} via {@link DocumentScopeGuard}. */
    public Document findOne(KeycloakUser user, UUID id) {
        return documentScopeGuard.requireVisible(user, id);
    }

    public DocumentContent getContent(KeycloakUser user, UUID id) {
        Document document = findOne(user, id);
        if (!DocumentStatus.CLEAN.equals(document.getStatus()) || document.getStorageKey() == null) {
            throw new NotFoundException("Document not found");
        }

        var stream = objectStorageService.getObject(document.getStorageKey());

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(),
                user.sub(),
                "document.download",
                "document",
                document.getId().toString(),
                "success",
                auditRequestContext.method(),
                auditRequestContext.path(),
                200,
                auditRequestContext.ip(),
                null));

        return new DocumentContent(document, stream);
    }

    private Path bufferToTempFile(IngestSource.StreamOpener opener) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("upload-", ".tmp");
            try (InputStream in = opener.open()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return tempFile;
        } catch (IOException e) {
            if (tempFile != null) {
                deleteQuietly(tempFile);
            }
            throw new IllegalStateException("Failed to buffer upload to a temp file", e);
        } catch (RuntimeException e) {
            if (tempFile != null) {
                deleteQuietly(tempFile);
            }
            throw e;
        }
    }

    private ClamdService.ScanVerdict scan(Path tempFile) {
        try (InputStream in = Files.newInputStream(tempFile)) {
            return clamdService.scanStream(in);
        } catch (ScannerUnavailableException e) {
            throw scannerUnavailable();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read upload for scanning", e);
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort temp file cleanup
        }
    }

    private static String sha256Hex(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read upload for hashing", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ---- error bodies (match data-ingestion.md §3 API contract) -----------

    private static ApiException invalidFormat(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "INVALID_FORMAT");
        body.put("message", message);
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, body, message);
    }

    private static ApiException fileTooLarge(long maxFileSizeBytes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "FILE_TOO_LARGE");
        body.put("message", "Tệp vượt quá dung lượng tối đa cho phép (" + maxFileSizeBytes + " byte)");
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, body, "File too large");
    }

    private static ApiException malwareDetected(String signature) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "MALWARE_DETECTED");
        body.put("message", "Phát hiện mã độc (" + signature + ")");
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, body, "Malware detected");
    }

    private static ApiException checksumMismatch() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "CHECKSUM_MISMATCH");
        body.put("message", "Tệp nguồn không khớp mã kiểm tra đã lưu");
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, body, "Checksum mismatch");
    }

    private static ApiException scannerUnavailable() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "SCANNER_UNAVAILABLE");
        body.put("message", "Trình quét mã độc không khả dụng");
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, body, "Malware scanner unavailable");
    }

    /**
     * DEV addition: the spec's error table (§3) does not enumerate a code for
     * a SeaweedFS write failure (§4.2 step 8b) - it is an infrastructure
     * fault, not a documented business outcome. Surfacing it as a clean 500
     * with a {@code {code,message}} body (instead of letting the raw
     * exception propagate to Spring Boot's default error page) keeps the
     * error response shape consistent with every other failure in this API.
     */
    private static ApiException storageWriteFailed(Throwable cause) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "STORAGE_WRITE_FAILED");
        body.put("message", "Không thể ghi tệp vào kho lưu trữ, vui lòng thử lại");
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, body, cause.getMessage());
    }
}
