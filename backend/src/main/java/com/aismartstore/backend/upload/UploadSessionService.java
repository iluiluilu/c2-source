package com.aismartstore.backend.upload;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentsService;
import com.aismartstore.backend.documents.IngestOutcome;
import com.aismartstore.backend.ingest.BatchKind;
import com.aismartstore.backend.ingest.UploadBatch;
import com.aismartstore.backend.ingest.UploadBatchService;
import com.aismartstore.backend.ingest.dto.InitUploadSessionDto;
import com.aismartstore.backend.personal.PersonalFile;
import com.aismartstore.backend.personal.PersonalService;
import com.aismartstore.backend.personal.dto.OpenPersonalUploadSessionDto;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * chunked-file-upload-01-ingest-chunked-upload.md §4: the
 * {@code init -> chunk -> status -> complete} state machine behind
 * {@code upload_session}. Shared verbatim by
 * chunked-file-upload-02-personal-chunked-upload.md - only {@link #init}
 * (called from a domain-specific controller endpoint) and the
 * {@code BatchKind.PERSONAL} arm inside {@link #complete} are spec-specific;
 * everything else here is domain-neutral by design (§0).
 */
@Service
public class UploadSessionService {

    private static final Logger log = LoggerFactory.getLogger(UploadSessionService.class);

    private final UploadSessionRepository uploadSessionRepository;
    private final UploadBatchService uploadBatchService;
    private final DocumentsService documentsService;
    private final PersonalService personalService;
    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;

    public UploadSessionService(
            UploadSessionRepository uploadSessionRepository,
            UploadBatchService uploadBatchService,
            DocumentsService documentsService,
            PersonalService personalService,
            SystemConfigService systemConfigService,
            ObjectMapper objectMapper,
            AuditService auditService,
            AuditRequestContext auditRequestContext) {
        this.uploadSessionRepository = uploadSessionRepository;
        this.uploadBatchService = uploadBatchService;
        this.documentsService = documentsService;
        this.personalService = personalService;
        this.systemConfigService = systemConfigService;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
    }

    /** Result of {@link #complete} - the HTTP status/body the controller should send as-is. */
    public record CompleteResult(int httpStatus, Map<String, Object> body) {
    }

    // ---- init (§4.1) -------------------------------------------------------

    @Transactional
    public UploadSession init(KeycloakUser user, UUID batchId, InitUploadSessionDto dto) {
        UploadBatch batch = uploadBatchService.loadOpenBatch(batchId);
        if (batch.getKind() != BatchKind.WAREHOUSE) {
            // Same response as an unknown batch id (§3 "phòng thủ") - a chunked
            // ingest upload session must never be opened against a personal batch.
            throw batchNotFound();
        }

        long maxFileSizeBytes = systemConfigService.getLong(SystemConfigDefaults.INGEST_MAX_FILE_SIZE_BYTES);
        if (dto.declaredSizeBytes() > maxFileSizeBytes) {
            throw fileTooLarge(maxFileSizeBytes);
        }

        long chunkSizeBytes = systemConfigService.getLong(SystemConfigDefaults.UPLOAD_CHUNK_SIZE_BYTES);

        Path tempFile = createTempFile();
        OffsetDateTime now = OffsetDateTime.now();

        UploadSession session = new UploadSession();
        session.setBatchId(batchId);
        session.setOriginalName(dto.originalName());
        session.setRelativePath(dto.relativePath() == null ? "" : dto.relativePath());
        session.setDeclaredSizeBytes(dto.declaredSizeBytes());
        session.setReceivedBytes(0);
        session.setChunkSizeBytes(chunkSizeBytes);
        session.setDeclaredMimeType(dto.mimeType());
        session.setNote(dto.note());
        session.setTempFilePath(tempFile.toString());
        session.setStatus(UploadSessionStatus.OPEN);
        session.setCreatedBySub(user.sub());
        session.setCreatedByUsername(user.preferredUsername());
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setExpiresAt(now.plusHours(24));
        UploadSession saved = uploadSessionRepository.save(session);

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "ingest.upload_session.init", "upload_session",
                saved.getId().toString(), "success", auditRequestContext.method(), auditRequestContext.path(), 201,
                auditRequestContext.ip(),
                Map.of(
                        "batchId", batchId.toString(),
                        "originalName", dto.originalName(),
                        "declaredSizeBytes", dto.declaredSizeBytes())));
        return saved;
    }

    /**
     * chunked-file-upload-02-personal-chunked-upload.md §4.1: init for the
     * personal domain. Batch lookup is owner-scoped AND kind-scoped
     * ({@link PersonalService#loadOwnedOpenBatch}), so - unlike {@link #init}
     * above - there is no separate explicit {@code kind == PERSONAL} assert:
     * a warehouse batch id (even one owned by this same user) already comes
     * back as "not found" from that query. No quota check here (AC-7): quota
     * is only ever evaluated inside {@code PersonalService#uploadFileInBatch},
     * called once from {@link #complete}.
     */
    @Transactional
    public UploadSession initPersonal(KeycloakUser user, UUID batchId, OpenPersonalUploadSessionDto dto) {
        UploadBatch batch = personalService.loadOwnedOpenBatch(user, batchId);

        if (dto.folderId() != null) {
            personalService.assertOwnedFolder(user, dto.folderId());
        }

        long maxFileSizeBytes = systemConfigService.getLong(SystemConfigDefaults.INGEST_MAX_FILE_SIZE_BYTES);
        if (dto.declaredSizeBytes() > maxFileSizeBytes) {
            throw fileTooLarge(maxFileSizeBytes);
        }

        long chunkSizeBytes = systemConfigService.getLong(SystemConfigDefaults.UPLOAD_CHUNK_SIZE_BYTES);

        Path tempFile = createTempFile();
        OffsetDateTime now = OffsetDateTime.now();

        UploadSession session = new UploadSession();
        session.setBatchId(batch.getId());
        session.setOriginalName(dto.originalName());
        session.setRelativePath("");
        session.setDeclaredSizeBytes(dto.declaredSizeBytes());
        session.setReceivedBytes(0);
        session.setChunkSizeBytes(chunkSizeBytes);
        session.setDeclaredMimeType(dto.mimeType());
        session.setNote(null);
        session.setTargetPersonalFolderId(dto.folderId());
        session.setTempFilePath(tempFile.toString());
        session.setStatus(UploadSessionStatus.OPEN);
        session.setCreatedBySub(user.sub());
        session.setCreatedByUsername(user.preferredUsername());
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setExpiresAt(now.plusHours(24));
        UploadSession saved = uploadSessionRepository.save(session);

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "personal.upload_session.init", "upload_session",
                saved.getId().toString(), "success", auditRequestContext.method(), auditRequestContext.path(), 201,
                auditRequestContext.ip(),
                Map.of(
                        "batchId", batchId.toString(),
                        "originalName", dto.originalName(),
                        "declaredSizeBytes", dto.declaredSizeBytes())));
        return saved;
    }

    // ---- chunk (§4.2) -------------------------------------------------------

    /**
     * Appends one chunk. Streams straight from {@code body} into the temp
     * file (no {@code byte[]} buffering of the whole chunk) and is bounded to
     * at most {@code chunkSizeBytes + 1} bytes read off the wire regardless
     * of what the client claims/sends, so a misbehaving client can never make
     * this write an unbounded amount to disk before the overflow check below
     * runs.
     */
    @Transactional
    public UploadSession appendChunk(KeycloakUser user, UUID sessionId, long offset, InputStream body) {
        requireOwned(user, sessionId);
        UploadSession session = uploadSessionRepository.findByIdForUpdate(sessionId).orElseThrow(this::forbidden);

        if (!UploadSessionStatus.OPEN.equals(session.getStatus())) {
            throw notOpen();
        }
        if (offset != session.getReceivedBytes()) {
            throw offsetMismatch();
        }

        Path path = Path.of(session.getTempFilePath());
        long hardCap = session.getChunkSizeBytes() + 1;
        long written = writeBounded(path, body, hardCap);

        if (written > session.getChunkSizeBytes() || session.getReceivedBytes() + written > session.getDeclaredSizeBytes()) {
            truncateBack(path, session.getReceivedBytes());
            throw overflow();
        }

        session.setReceivedBytes(session.getReceivedBytes() + written);
        OffsetDateTime now = OffsetDateTime.now();
        session.setUpdatedAt(now);
        session.setExpiresAt(now.plusHours(24));
        return uploadSessionRepository.save(session);
    }

    private static long writeBounded(Path path, InputStream body, long hardCap) {
        try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.APPEND)) {
            byte[] buffer = new byte[8192];
            long written = 0;
            while (written < hardCap) {
                int toRead = (int) Math.min(buffer.length, hardCap - written);
                int read = body.read(buffer, 0, toRead);
                if (read == -1) {
                    break;
                }
                out.write(buffer, 0, read);
                written += read;
            }
            return written;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append chunk to temp file", e);
        }
    }

    private static void truncateBack(Path path, long lengthBefore) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.truncate(lengthBefore);
        } catch (IOException e) {
            log.error("Failed to truncate temp file {} back to {} bytes after a CHUNK_OVERFLOW", path, lengthBefore, e);
        }
    }

    // ---- status (§4.3) ------------------------------------------------------

    public UploadSession getStatus(KeycloakUser user, UUID sessionId) {
        return requireOwned(user, sessionId);
    }

    // ---- complete (§4.4) -----------------------------------------------------

    /**
     * Deliberately NOT {@code @Transactional}: it calls
     * {@link DocumentsService#ingestBatchFile}, whose pending-row/putObject/
     * clean-row steps are written to commit immediately, independent of any
     * surrounding transaction (see that method's Javadoc). Wrapping this
     * method would silently defer those commits until this method returns,
     * defeating that crash-safety design. The session-status bookkeeping
     * below runs as its own small auto-committed writes instead.
     */
    public CompleteResult complete(KeycloakUser user, UUID sessionId) {
        UploadSession session = requireOwned(user, sessionId);

        if (UploadSessionStatus.COMPLETED.equals(session.getStatus())) {
            return replay(session);
        }
        if (UploadSessionStatus.ABORTED.equals(session.getStatus())
                || UploadSessionStatus.EXPIRED.equals(session.getStatus())) {
            throw notOpen();
        }
        if (session.getReceivedBytes() != session.getDeclaredSizeBytes()) {
            throw incomplete();
        }

        // batch may have expired/closed while chunks were still trickling in -
        // loadOpenBatch throws the same BATCH_EXPIRED/BATCH_CLOSED as whole-file.
        UploadBatch batch = uploadBatchService.loadOpenBatch(session.getBatchId());

        Path tempFile = Path.of(session.getTempFilePath());
        try {
            CompleteResult result = switch (batch.getKind()) {
                case WAREHOUSE -> completeWarehouse(user, session, tempFile);
                case PERSONAL -> completePersonal(user, session, tempFile);
            };
            session.setStatus(UploadSessionStatus.COMPLETED);
            session.setResultBody(result.body());
            session.setUpdatedAt(OffsetDateTime.now());
            uploadSessionRepository.save(session);
            return result;
        } catch (ApiException e) {
            // A recognized business rejection (format/malware/size/scanner) -
            // the file "was processed", it just didn't make it in. Store enough
            // to replay the same rejection later (§4.4 step 7) instead of
            // leaving result_body null.
            Map<String, Object> rejected = new LinkedHashMap<>();
            rejected.put("status", "REJECTED");
            rejected.put("httpStatus", e.getStatus().value());
            Object body = e.getBody();
            if (body instanceof Map<?, ?> map) {
                rejected.put("code", map.get("code"));
                rejected.put("message", map.get("message"));
            } else {
                rejected.put("message", e.getMessage());
            }
            session.setStatus(UploadSessionStatus.COMPLETED);
            session.setResultBody(rejected);
            session.setUpdatedAt(OffsetDateTime.now());
            uploadSessionRepository.save(session);
            throw e;
        } catch (RuntimeException e) {
            log.error("Unexpected failure completing upload session {}", sessionId, e);
            session.setStatus(UploadSessionStatus.ABORTED);
            session.setUpdatedAt(OffsetDateTime.now());
            uploadSessionRepository.save(session);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to complete upload session");
        } finally {
            deleteQuietly(tempFile);
        }
    }

    private CompleteResult completeWarehouse(KeycloakUser user, UploadSession session, Path tempFile) {
        PathMultipartFile wrapped =
                new PathMultipartFile(tempFile, session.getOriginalName(), session.getDeclaredMimeType());
        IngestOutcome outcome = documentsService.ingestBatchFile(
                user, session.getBatchId(), wrapped, session.getRelativePath(), session.getNote());

        Map<String, Object> rawBody;
        int httpStatus;
        if (outcome instanceof IngestOutcome.Skipped skipped) {
            rawBody = new LinkedHashMap<>();
            rawBody.put("status", "SKIPPED");
            rawBody.put("reason", skipped.reason());
            rawBody.put("documentId", skipped.documentId());
            httpStatus = HttpStatus.OK.value();
        } else {
            IngestOutcome.Ingested ingested = (IngestOutcome.Ingested) outcome;
            Document document = ingested.document();
            rawBody = new LinkedHashMap<>();
            rawBody.put("status", "INGESTED");
            rawBody.put("document", document);
            rawBody.put("duplicateOf", ingested.duplicateOf().stream().map(Document::getId).toList());
            httpStatus = HttpStatus.CREATED.value();
        }
        // Normalize to plain JSON-compatible types (String/Number/Map/List)
        // before it is handed to Hibernate's JSONB writer/stored on the
        // entity, so replay (§4.4 step 2) returns exactly the same shape
        // regardless of how the JSON type mapper round-trips a raw Document.
        return new CompleteResult(httpStatus, toJsonMap(rawBody));
    }

    /**
     * chunked-file-upload-02-personal-chunked-upload.md §4.4: mirrors
     * {@link #completeWarehouse}, but dispatches to
     * {@code PersonalService.uploadFileInBatch} - the response shape matches
     * {@code POST /personal/batches/{id}/files} ({@code PersonalController
     * .UploadedFileView}), NOT {@code IngestFileResult} (§3 "đính chính").
     */
    private CompleteResult completePersonal(KeycloakUser user, UploadSession session, Path tempFile) {
        PathMultipartFile wrapped =
                new PathMultipartFile(tempFile, session.getOriginalName(), session.getDeclaredMimeType());
        PersonalFile saved = personalService.uploadFileInBatch(
                user, session.getBatchId(), session.getTargetPersonalFolderId(), wrapped);

        Map<String, Object> rawBody = new LinkedHashMap<>();
        rawBody.put("status", "STORED");
        rawBody.put("file", saved);
        // Same JSON-normalization rationale as completeWarehouse above.
        return new CompleteResult(HttpStatus.CREATED.value(), toJsonMap(rawBody));
    }

    private CompleteResult replay(UploadSession session) {
        Map<String, Object> body = session.getResultBody();
        if (body == null) {
            // Should not happen given the write path above always sets it - fail
            // loudly rather than silently re-running the pipeline.
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Upload session is completed but has no stored result");
        }
        if ("REJECTED".equals(body.get("status"))) {
            Map<String, Object> errorBody = new LinkedHashMap<>();
            errorBody.put("code", body.get("code"));
            errorBody.put("message", body.get("message"));
            int httpStatus = body.get("httpStatus") instanceof Number n ? n.intValue() : 422;
            throw new ApiException(HttpStatus.valueOf(httpStatus), errorBody, String.valueOf(body.get("message")));
        }
        int httpStatus = "SKIPPED".equals(body.get("status")) ? HttpStatus.OK.value() : HttpStatus.CREATED.value();
        return new CompleteResult(httpStatus, body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toJsonMap(Object o) {
        return objectMapper.convertValue(o, Map.class);
    }

    // ---- cleanup (chunked-file-upload-03-session-cleanup.md §4.2) -------------

    /**
     * Read-only, deliberately NOT {@code @Transactional}: only fetches ids, so
     * no row lock/transaction is held across the loop of {@link #expireIfDue}
     * calls a caller (e.g. {@code UploadCleanupJob}) makes over the result.
     */
    public List<UUID> findExpiredSessionIds(int limit) {
        return uploadSessionRepository
                .findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                        UploadSessionStatus.OPEN, OffsetDateTime.now(), PageRequest.of(0, limit))
                .stream()
                .map(UploadSession::getId)
                .toList();
    }

    public enum TempFileDeleteResult {
        DELETED,
        ALREADY_MISSING,
        DELETE_FAILED
    }

    /** Carries just enough for the caller to delete the temp file after the DB transaction below has committed. */
    public record ExpiryOutcome(String tempFilePath) {

        TempFileDeleteResult deleteTempFile() {
            try {
                boolean existed = Files.deleteIfExists(Path.of(tempFilePath));
                return existed ? TempFileDeleteResult.DELETED : TempFileDeleteResult.ALREADY_MISSING;
            } catch (IOException e) {
                log.warn("Failed to delete temp file {} for expired upload_session", tempFilePath, e);
                return TempFileDeleteResult.DELETE_FAILED; // never throw - the job must move on to the next session
            }
        }
    }

    /**
     * Race-safe expiry (§4.2, §7): locks the row with {@link
     * UploadSessionRepository#findByIdForUpdate} and RE-CHECKS {@code status
     * == OPEN && expires_at < now()} inside that lock before flipping to
     * {@code EXPIRED}. This is what protects against a chunk PUT
     * (§4.2 of spec 01, which extends {@code expires_at} by 24h on every
     * successful write) racing the cleanup job: whichever of the two wins the
     * row lock first "sees" the truth, so a session that was just renewed
     * never gets expired out from under an in-flight upload.
     */
    @Transactional
    public ExpiryOutcome expireIfDue(UUID sessionId) {
        UploadSession session = uploadSessionRepository.findByIdForUpdate(sessionId).orElse(null);
        if (session == null) {
            return null;
        }
        if (!UploadSessionStatus.OPEN.equals(session.getStatus())) {
            return null;
        }
        if (!OffsetDateTime.now().isAfter(session.getExpiresAt())) {
            return null; // renewed (or about to be) - not actually due anymore
        }
        session.setStatus(UploadSessionStatus.EXPIRED);
        session.setUpdatedAt(OffsetDateTime.now());
        uploadSessionRepository.save(session);
        return new ExpiryOutcome(session.getTempFilePath());
    }

    // ---- internals ------------------------------------------------------

    /**
     * §4.2 step 1: 403 for both "no such session" and "not yours" - never
     * distinguishes the two in the response (AC-7), so this covers chunk,
     * status and complete uniformly.
     */
    private UploadSession requireOwned(KeycloakUser user, UUID sessionId) {
        return uploadSessionRepository
                .findByIdAndCreatedBySub(sessionId, user.sub())
                .orElseThrow(this::forbidden);
    }

    private static Path createTempFile() {
        try {
            return Files.createTempFile("upload-session-", ".tmp");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create a temp file for a new upload session", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete upload-session temp file {}", path, e);
        }
    }

    // ---- error bodies (match spec §3 API contract) -----------------------

    private ApiException forbidden() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "UPLOAD_SESSION_FORBIDDEN");
        body.put("message", "Không có quyền truy cập phiên tải này");
        return new ApiException(HttpStatus.FORBIDDEN, body, "Upload session forbidden");
    }

    private static ApiException notOpen() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "UPLOAD_SESSION_NOT_OPEN");
        body.put("message", "Phiên tải không còn ở trạng thái mở");
        return new ApiException(HttpStatus.CONFLICT, body, "Upload session not open");
    }

    private static ApiException offsetMismatch() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "CHUNK_OFFSET_MISMATCH");
        body.put("message", "Vị trí chunk không khớp với dữ liệu đã nhận, vui lòng đồng bộ lại trạng thái");
        return new ApiException(HttpStatus.CONFLICT, body, "Chunk offset mismatch");
    }

    private static ApiException overflow() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "CHUNK_OVERFLOW");
        body.put("message", "Dữ liệu nhận được vượt quá dung lượng tệp đã khai báo");
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, body, "Chunk overflow");
    }

    private static ApiException incomplete() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "UPLOAD_SESSION_INCOMPLETE");
        body.put("message", "Chưa nhận đủ dữ liệu tệp, không thể hoàn tất");
        return new ApiException(HttpStatus.CONFLICT, body, "Upload session incomplete");
    }

    private static ApiException batchNotFound() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "BATCH_NOT_FOUND");
        body.put("message", "Không tìm thấy lượt tải");
        return new ApiException(HttpStatus.NOT_FOUND, body, "Batch not found");
    }

    private static ApiException fileTooLarge(long maxFileSizeBytes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "FILE_TOO_LARGE");
        body.put("message", "Tệp vượt quá dung lượng tối đa cho phép (" + maxFileSizeBytes + " byte)");
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, body, "File too large");
    }
}
