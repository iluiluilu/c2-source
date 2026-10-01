package com.aismartstore.backend.personal;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.BadRequestException;
import com.aismartstore.backend.displayname.NameCheckRateLimiter;
import com.aismartstore.backend.displayname.NameCheckRepository;
import com.aismartstore.backend.displayname.NameCheckRequest;
import com.aismartstore.backend.displayname.NameCheckSupport;
import com.aismartstore.backend.ingest.UploadBatch;
import com.aismartstore.backend.personal.dto.CreatePersonalFolderDto;
import com.aismartstore.backend.personal.dto.MovePersonalFileDto;
import com.aismartstore.backend.personal.dto.OpenPersonalBatchDto;
import com.aismartstore.backend.personal.dto.OpenPersonalUploadSessionDto;
import com.aismartstore.backend.personal.ingest.PersonalToWarehouseService;
import com.aismartstore.backend.upload.UploadSession;
import com.aismartstore.backend.upload.UploadSessionService;
import com.aismartstore.backend.warehouse.FolderNode;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Java port of the NestJS backend's personal/personal.controller.ts (FR-14),
 * extended per docs/03-dev/specs/personal-storage-quota.md §3 (2-step batch
 * upload). Every route admits all three roles - this does NOT breach NFR-07
 * because every query is owner-scoped to the caller's own sub, so an admin
 * only ever sees their own private files, never warehouse business content.
 */
@RestController
@RequestMapping("/personal")
public class PersonalController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PersonalController.class);

    private static final String ALL_ROLES = "hasAuthority('PERM_personal.manage')";

    private final PersonalService personalService;
    private final UploadSessionService uploadSessionService;
    private final PersonalToWarehouseService personalToWarehouseService;
    private final NameCheckRateLimiter nameCheckRateLimiter;
    private final NameCheckRepository nameCheckRepository;

    public PersonalController(
            PersonalService personalService,
            UploadSessionService uploadSessionService,
            PersonalToWarehouseService personalToWarehouseService,
            NameCheckRateLimiter nameCheckRateLimiter,
            NameCheckRepository nameCheckRepository) {
        this.nameCheckRateLimiter = nameCheckRateLimiter;
        this.nameCheckRepository = nameCheckRepository;
        this.personalService = personalService;
        this.uploadSessionService = uploadSessionService;
        this.personalToWarehouseService = personalToWarehouseService;
    }

    @GetMapping("/quota")
    @PreAuthorize(ALL_ROLES)
    public PersonalService.QuotaInfo quota(@CurrentUser KeycloakUser user) {
        return personalService.getQuota(user);
    }

    // --------------------------------------------------------------- folders

    @GetMapping("/folders/tree")
    @PreAuthorize(ALL_ROLES)
    public List<FolderNode> tree(@CurrentUser KeycloakUser user) {
        return personalService.getTree(user);
    }

    @PostMapping("/folders")
    @PreAuthorize(ALL_ROLES)
    public PersonalFolder createFolder(@CurrentUser KeycloakUser user, @Valid @RequestBody CreatePersonalFolderDto dto) {
        return personalService.createFolder(user, dto.name(), dto.parentId());
    }

    /**
     * {@code parentId} needs true 3-state semantics (omitted = unchanged,
     * explicit null = move to root, a UUID = move under that folder) that a
     * plain DTO field can't express, so the body is read as a raw map and
     * key-presence decides intent (see plan's Phase-4 note).
     */
    @PatchMapping("/folders/{id}")
    @PreAuthorize(ALL_ROLES)
    public PersonalFolder updateFolder(
            @CurrentUser KeycloakUser user,
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> safeBody = body != null ? body : Map.of();

        boolean nameProvided = safeBody.containsKey("name");
        String name = nameProvided ? asName(safeBody.get("name")) : null;

        boolean parentIdProvided = safeBody.containsKey("parentId");
        UUID parentId = parentIdProvided && safeBody.get("parentId") != null ? asUuid(safeBody.get("parentId")) : null;

        return personalService.updateFolder(user, id, nameProvided, name, parentIdProvided, parentId);
    }

    @DeleteMapping("/folders/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(ALL_ROLES)
    public void deleteFolder(@CurrentUser KeycloakUser user, @PathVariable UUID id) {
        personalService.deleteFolder(user, id);
    }

    // --------------------------------------------------------------- batches

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

    public record CloseSummary(int filesStored, long actualTotalBytes) {
    }

    public record CloseBatchView(UUID batchId, String status, CloseSummary summary) {
    }

    public record UploadedFileView(String status, PersonalFile file) {
    }

    /**
     * chunked-file-upload-02-personal-chunked-upload.md §3: same response
     * shape as {@code IngestController.OpenUploadSessionView} - kept as a
     * separate local record (not imported cross-package) to match this
     * controller's existing style of declaring its own response records
     * (e.g. {@code OpenBatchView} vs. ingest's).
     */
    public record OpenUploadSessionView(
            UUID sessionId, long chunkSizeBytes, long receivedBytes, OffsetDateTime expiresAt) {
        static OpenUploadSessionView of(UploadSession s) {
            return new OpenUploadSessionView(s.getId(), s.getChunkSizeBytes(), s.getReceivedBytes(), s.getExpiresAt());
        }
    }

    @PostMapping("/batches")
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<OpenBatchView> openBatch(
            @CurrentUser KeycloakUser user, @Valid @RequestBody OpenPersonalBatchDto dto) {
        UploadBatch batch =
                personalService.openBatch(user, dto.folderId(), dto.declaredFileCount(), dto.declaredTotalBytes());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new OpenBatchView(batch.getId(), batch.getStatus(), batch.getExpiresAt()));
    }

    @PostMapping(value = "/batches/{batchId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<UploadedFileView> uploadFileInBatch(
            @CurrentUser KeycloakUser user,
            @PathVariable UUID batchId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) UUID folderId,
            @RequestParam(required = false, defaultValue = "") String relativePath,
            @RequestParam(required = false) String displayName) {
        if (file.isEmpty()) {
            throw new BadRequestException("Missing 'file' field");
        }
        PersonalFile saved =
                personalService.uploadFileInBatch(user, batchId, folderId, file, relativePath, displayName);
        return ResponseEntity.status(HttpStatus.CREATED).body(new UploadedFileView("STORED", saved));
    }

    /**
     * chunked-file-upload-02-personal-chunked-upload.md §3/§4.1: init for the
     * chunked-upload protocol whose remaining 3 endpoints
     * ({@code chunks}/{@code status}/{@code complete}) live under the
     * domain-neutral {@code /upload-sessions/**} (spec 01).
     */
    /** SPEC-folder-upload-display-name BE-5: which candidate names are already taken in the caller's personal folder. */
    @PostMapping("/name-check")
    @PreAuthorize(ALL_ROLES)
    public List<NameCheckRequest.Result> nameCheck(
            @CurrentUser KeycloakUser user, @Valid @RequestBody NameCheckRequest request) {
        nameCheckRateLimiter.check(user.sub(), "personal.name-check");
        if (request.folderId() != null) {
            personalService.assertOwnedFolder(user, request.folderId());
        }
        List<NameCheckRepository.Candidate> candidates = NameCheckSupport.toCandidates(request);
        boolean[] taken = nameCheckRepository.personalTaken(user.sub(), request.folderId(), candidates);
        log.info("name-check endpoint=personal files={} taken={}", taken.length, NameCheckSupport.count(taken));
        return NameCheckSupport.toResults(taken);
    }

    @PostMapping("/batches/{batchId}/upload-sessions")
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<OpenUploadSessionView> initUploadSession(
            @CurrentUser KeycloakUser user,
            @PathVariable UUID batchId,
            @Valid @RequestBody OpenPersonalUploadSessionDto dto) {
        UploadSession session = uploadSessionService.initPersonal(user, batchId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(OpenUploadSessionView.of(session));
    }

    @PostMapping("/batches/{batchId}/close")
    @PreAuthorize(ALL_ROLES)
    public CloseBatchView closeBatch(@CurrentUser KeycloakUser user, @PathVariable UUID batchId) {
        UploadBatch batch = personalService.closeBatch(user, batchId);
        return new CloseBatchView(
                batch.getId(), batch.getStatus(),
                new CloseSummary(batch.getActualFileCount(), batch.getActualTotalBytes()));
    }

    @GetMapping("/batches/{batchId}")
    @PreAuthorize(ALL_ROLES)
    public BatchStatusView batchStatus(@CurrentUser KeycloakUser user, @PathVariable UUID batchId) {
        return BatchStatusView.of(personalService.getBatch(user, batchId));
    }

    // ----------------------------------------------------------------- files

    @GetMapping("/files")
    @PreAuthorize(ALL_ROLES)
    public List<PersonalFileView> listFiles(@CurrentUser KeycloakUser user) {
        List<PersonalFile> files = personalService.listFiles(user);
        // personal-to-warehouse.md AC-21: one query for the whole list, no N+1.
        Set<UUID> added = files.isEmpty() ? Set.of() : personalToWarehouseService.addedToWarehouseIds(user);
        return files.stream().map(f -> PersonalFileView.of(f, added.contains(f.getId()))).toList();
    }

    @PatchMapping("/files/{id}")
    @PreAuthorize(ALL_ROLES)
    public PersonalFile moveFile(
            @CurrentUser KeycloakUser user, @PathVariable UUID id, @Valid @RequestBody MovePersonalFileDto dto) {
        return personalService.moveFile(user, id, dto.folderId());
    }

    @GetMapping("/files/{id}/content")
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<InputStreamResource> content(@CurrentUser KeycloakUser user, @PathVariable UUID id) {
        PersonalService.PersonalFileContent content = personalService.getContent(user, id);
        PersonalFile file = content.file();

        String encodedName = URLEncoder.encode(file.getOriginalName(), StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getMimeType()))
                .contentLength(file.getSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(new InputStreamResource(content.stream()));
    }

    @DeleteMapping("/files/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(ALL_ROLES)
    public void deleteFile(@CurrentUser KeycloakUser user, @PathVariable UUID id) {
        personalService.deleteFile(user, id);
    }

    private static String asName(Object value) {
        if (!(value instanceof String s) || s.isBlank() || s.length() > 255) {
            throw new BadRequestException("name must be a non-blank string of at most 255 characters");
        }
        return s;
    }

    private static UUID asUuid(Object value) {
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("parentId must be a valid UUID");
        }
    }
}
