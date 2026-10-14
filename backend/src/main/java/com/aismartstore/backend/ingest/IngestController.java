package com.aismartstore.backend.ingest;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.BadRequestException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.displayname.NameCheckRateLimiter;
import com.aismartstore.backend.displayname.NameCheckRepository;
import com.aismartstore.backend.displayname.NameCheckRequest;
import com.aismartstore.backend.displayname.NameCheckSupport;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentsService;
import com.aismartstore.backend.documents.IngestOutcome;
import com.aismartstore.backend.ingest.dto.InitUploadSessionDto;
import com.aismartstore.backend.ingest.dto.OpenBatchDto;
import com.aismartstore.backend.notes.NoteConstraints;
import com.aismartstore.backend.personal.ingest.FromPersonalDto;
import com.aismartstore.backend.personal.ingest.PersonalToWarehouseService;
import com.aismartstore.backend.scanning.AllowlistService;
import com.aismartstore.backend.upload.UploadSession;
import com.aismartstore.backend.warehouse.WarehouseService;
import com.aismartstore.backend.upload.UploadSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * data-ingestion.md §3: the batch ingest API - {@code POST /documents}
 * (multipart, single file) is retired in favour of this 2-step flow, used
 * for both a single file (a 1-file batch) and a whole folder (Story 1/2).
 * Every endpoint gates by {@code PERM_document.upload} -
 * {@code /limits}/{@code /allowed-formats} included, even though their
 * payload isn't sensitive. Mặc định {@code CAN_BO} + {@code LANH_DAO} (R2,
 * cấp qua V35__r2_business_parity.sql); {@code QUAN_TRI} (R3) chỉnh được
 * qua màn RBAC.
 */
@RestController
@RequestMapping("/ingest")
@Validated
public class IngestController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(IngestController.class);

    private final UploadBatchService uploadBatchService;
    private final DocumentsService documentsService;
    private final AllowlistService allowlistService;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;
    private final UploadSessionService uploadSessionService;
    private final PersonalToWarehouseService personalToWarehouseService;
    private final NameCheckRateLimiter nameCheckRateLimiter;
    private final NameCheckRepository nameCheckRepository;
    private final WarehouseService warehouseService;

    public IngestController(
            UploadBatchService uploadBatchService,
            DocumentsService documentsService,
            AllowlistService allowlistService,
            AuditService auditService,
            AuditRequestContext auditRequestContext,
            UploadSessionService uploadSessionService,
            PersonalToWarehouseService personalToWarehouseService,
            NameCheckRateLimiter nameCheckRateLimiter,
            NameCheckRepository nameCheckRepository,
            WarehouseService warehouseService) {
        this.uploadBatchService = uploadBatchService;
        this.documentsService = documentsService;
        this.allowlistService = allowlistService;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
        this.uploadSessionService = uploadSessionService;
        this.personalToWarehouseService = personalToWarehouseService;
        this.nameCheckRateLimiter = nameCheckRateLimiter;
        this.nameCheckRepository = nameCheckRepository;
        this.warehouseService = warehouseService;
    }

    public record LimitsView(
            long maxFilesPerBatch, long maxBatchSizeBytes, long maxFileSizeBytes, long chunkThresholdBytes) {
    }

    public record FormatView(String mimeType, List<String> extensions) {
    }

    public record OpenBatchView(UUID batchId, String status, OffsetDateTime expiresAt) {
    }

    public record BatchStatusView(
            UUID batchId,
            String status,
            int declaredFileCount,
            long declaredTotalBytes,
            int actualFileCount,
            long actualTotalBytes,
            OffsetDateTime expiresAt) {
        static BatchStatusView of(UploadBatch b) {
            return new BatchStatusView(
                    b.getId(), b.getStatus(), b.getDeclaredFileCount(), b.getDeclaredTotalBytes(),
                    b.getActualFileCount(), b.getActualTotalBytes(), b.getExpiresAt());
        }
    }

    public record CloseSummary(int filesAccepted, int filesSkipped, int filesRejected, long actualTotalBytes) {
    }

    public record CloseBatchView(UUID batchId, String status, CloseSummary summary) {
    }

    /** POST /ingest/batches/{batchId}/upload-sessions response (chunked-file-upload-01 §3/§4.1). */
    public record OpenUploadSessionView(
            UUID sessionId, long chunkSizeBytes, long receivedBytes, OffsetDateTime expiresAt) {
        static OpenUploadSessionView of(UploadSession s) {
            return new OpenUploadSessionView(s.getId(), s.getChunkSizeBytes(), s.getReceivedBytes(), s.getExpiresAt());
        }
    }

    @GetMapping("/limits")
    @PreAuthorize("hasAuthority('PERM_document.upload')")
    public LimitsView limits() {
        UploadBatchService.Limits limits = uploadBatchService.getLimits();
        return new LimitsView(
                limits.maxFilesPerBatch(),
                limits.maxBatchSizeBytes(),
                limits.maxFileSizeBytes(),
                limits.chunkThresholdBytes());
    }

    @GetMapping("/allowed-formats")
    @PreAuthorize("hasAuthority('PERM_document.upload')")
    public List<FormatView> allowedFormats() {
        return allowlistService.listAll().stream()
                .map(row -> new FormatView(row.getMimeType(), java.util.Arrays.asList(row.getExtensions())))
                .toList();
    }

    @PostMapping("/batches")
    @PreAuthorize("hasAuthority('PERM_document.upload')")
    public ResponseEntity<OpenBatchView> openBatch(@CurrentUser KeycloakUser user, @Valid @RequestBody OpenBatchDto dto) {
        UploadBatch batch = uploadBatchService.open(
                user, dto.targetFolderId(), dto.declaredFileCount(), dto.declaredTotalBytes());
        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "ingest.batch.open", "upload_batch",
                batch.getId().toString(), "success", auditRequestContext.method(), auditRequestContext.path(), 201,
                auditRequestContext.ip(),
                Map.of(
                        "targetFolderId", batch.getTargetFolderId().toString(),
                        "declaredFileCount", batch.getDeclaredFileCount(),
                        "declaredTotalBytes", batch.getDeclaredTotalBytes())));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new OpenBatchView(batch.getId(), batch.getStatus(), batch.getExpiresAt()));
    }

    @PostMapping(value = "/batches/{batchId}/files", consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('PERM_document.upload')")
    public ResponseEntity<Object> uploadFile(
            @CurrentUser KeycloakUser user,
            @PathVariable UUID batchId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false, defaultValue = "") String relativePath,
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false) @Size(max = NoteConstraints.NOTE_MAX_LENGTH) String note) {
        if (file.isEmpty()) {
            throw new BadRequestException("Chưa chọn tệp");
        }

        IngestOutcome outcome = documentsService.ingestBatchFile(user, batchId, file, relativePath, displayName, note);

        IngestOutcome.Ingested ingested = (IngestOutcome.Ingested) outcome;
        Document document = ingested.document();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", "INGESTED");
        body.put("document", document);
        body.put(
                "duplicateOf",
                ingested.duplicateOf().stream().map(Document::getId).toList());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * SPEC-folder-upload-display-name BE-5: read-only lookup of which candidate names are already
     * taken (live, non-trashed) in the target warehouse folder. Same permission as opening a batch.
     */
    @PostMapping("/name-check")
    @PreAuthorize("hasAuthority('PERM_document.upload')")
    public List<NameCheckRequest.Result> nameCheck(
            @CurrentUser KeycloakUser user, @Valid @RequestBody NameCheckRequest request) {
        nameCheckRateLimiter.check(user.sub(), "ingest.name-check");
        if (request.folderId() == null || !warehouseService.folderExists(request.folderId())) {
            throw new NotFoundException("Không tìm thấy thư mục");
        }
        List<NameCheckRepository.Candidate> candidates = NameCheckSupport.toCandidates(request);
        boolean[] taken = nameCheckRepository.warehouseTaken(request.folderId(), candidates);
        log.info("name-check endpoint=ingest files={} taken={}", taken.length, NameCheckSupport.count(taken));
        return NameCheckSupport.toResults(taken);
    }

    @PostMapping("/batches/{batchId}/upload-sessions")
    @PreAuthorize("hasAuthority('PERM_document.upload')")
    public ResponseEntity<OpenUploadSessionView> initUploadSession(
            @CurrentUser KeycloakUser user,
            @PathVariable UUID batchId,
            @Valid @RequestBody InitUploadSessionDto dto) {
        UploadSession session = uploadSessionService.init(user, batchId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(OpenUploadSessionView.of(session));
    }

    @PostMapping("/batches/{batchId}/close")
    @PreAuthorize("hasAuthority('PERM_document.upload')")
    public CloseBatchView closeBatch(@CurrentUser KeycloakUser user, @PathVariable UUID batchId) {
        UploadBatch batch = uploadBatchService.close(batchId);
        CloseSummary summary = new CloseSummary(
                batch.getAcceptedFileCount(),
                batch.getSkippedFileCount(),
                batch.getRejectedFileCount(),
                batch.getActualTotalBytes());
        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "ingest.batch.close", "upload_batch",
                batch.getId().toString(), "success", auditRequestContext.method(), auditRequestContext.path(), 200,
                auditRequestContext.ip(),
                Map.of(
                        "filesAccepted", summary.filesAccepted(),
                        "filesSkipped", summary.filesSkipped(),
                        "filesRejected", summary.filesRejected())));
        return new CloseBatchView(batch.getId(), batch.getStatus(), summary);
    }

    @GetMapping("/batches/{batchId}")
    @PreAuthorize("hasAuthority('PERM_document.upload')")
    public BatchStatusView batchStatus(@PathVariable UUID batchId) {
        return BatchStatusView.of(uploadBatchService.getStatus(batchId));
    }

    /**
     * personal-to-warehouse.md §3: supplement warehouse documents from the
     * caller's own personal storage. Returns immediately (202); the server
     * processes files sequentially in the background - poll the GET below.
     * Per-file audit ({@code ingest.from_personal.file}) is written by the worker.
     */
    @PostMapping("/from-personal")
    @PreAuthorize("hasAuthority('PERM_document.upload')")
    public ResponseEntity<PersonalToWarehouseService.StartResult> fromPersonal(
            @CurrentUser KeycloakUser user, @Valid @RequestBody FromPersonalDto dto) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(personalToWarehouseService.start(user, dto));
    }

    /** SPEC-folder-upload-display-name BE-7 (AC-18): name-check for a personal-to-warehouse selection. */
    @PostMapping("/from-personal/name-check")
    @PreAuthorize("hasAuthority('PERM_document.upload')")
    public List<PersonalToWarehouseService.NameCheckItem> fromPersonalNameCheck(
            @CurrentUser KeycloakUser user, @Valid @RequestBody FromPersonalDto dto) {
        nameCheckRateLimiter.check(user.sub(), "ingest.from-personal.name-check");
        List<PersonalToWarehouseService.NameCheckItem> result = personalToWarehouseService.nameCheck(user, dto);
        log.info("name-check endpoint=from-personal files={} taken={}",
                result.size(), result.stream().filter(PersonalToWarehouseService.NameCheckItem::taken).count());
        return result;
    }

    public record RenameItemDto(String displayName) {
    }

    /** AC-20: pick a new display name for an item that failed with DISPLAY_NAME_CONFLICT (max 3 times per item). */
    @PutMapping("/from-personal/{batchId}/items/{itemId}/display-name")
    @PreAuthorize("hasAuthority('PERM_document.upload')")
    public PersonalToWarehouseService.ItemView fromPersonalRenameItem(
            @CurrentUser KeycloakUser user,
            @PathVariable UUID batchId,
            @PathVariable UUID itemId,
            @Valid @RequestBody RenameItemDto dto) {
        return personalToWarehouseService.renameItem(user, batchId, itemId, dto.displayName());
    }

    @GetMapping("/from-personal/{batchId}")
    @PreAuthorize("hasAuthority('PERM_document.upload')")
    public PersonalToWarehouseService.BatchResultView fromPersonalResult(
            @CurrentUser KeycloakUser user, @PathVariable UUID batchId) {
        return personalToWarehouseService.getResult(user, batchId);
    }
}
