package com.aismartstore.backend.personal;

import com.aismartstore.backend.accounts.Account;
import com.aismartstore.backend.accounts.AccountsService;
import com.aismartstore.backend.accounts.AssignableRoles;
import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.common.ConflictException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.common.ServiceUnavailableException;
import com.aismartstore.backend.common.SqlErrors;
import com.aismartstore.backend.common.UnprocessableEntityException;
import com.aismartstore.backend.config.StorageProperties;
import com.aismartstore.backend.ingest.BatchKind;
import com.aismartstore.backend.ingest.UploadBatch;
import com.aismartstore.backend.ingest.UploadBatchRepository;
import com.aismartstore.backend.ingest.UploadBatchStatus;
import com.aismartstore.backend.scanning.ClamdService;
import com.aismartstore.backend.scanning.ScannerUnavailableException;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import com.aismartstore.backend.warehouse.FolderNode;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * Java port of the NestJS backend's personal/personal.service.ts (FR-14),
 * extended per docs/03-dev/specs/personal-storage-quota.md. Fully isolated
 * from warehouse/documents/tags/notes/case-files/OCR/STT/search - depends
 * only on scanning/storage/accounts(quota)/ingest(batch bookkeeping). Every
 * lookup is owner-scoped: a foreign id always resolves to 404, never 403
 * (existence must not leak).
 */
@Service
public class PersonalService {

    private static final Logger log = LoggerFactory.getLogger(PersonalService.class);

    private final PersonalFolderRepository folderRepository;
    private final PersonalFileRepository fileRepository;
    private final UploadBatchRepository uploadBatchRepository;
    private final ClamdService clamdService;
    private final ObjectStorageService objectStorageService;
    private final StorageProperties storageProperties;
    private final AccountsService accountsService;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final SystemConfigService systemConfigService;

    public PersonalService(
            PersonalFolderRepository folderRepository,
            PersonalFileRepository fileRepository,
            UploadBatchRepository uploadBatchRepository,
            ClamdService clamdService,
            ObjectStorageService objectStorageService,
            StorageProperties storageProperties,
            AccountsService accountsService,
            AuditService auditService,
            AuditRequestContext auditRequestContext,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager,
            SystemConfigService systemConfigService) {
        this.folderRepository = folderRepository;
        this.fileRepository = fileRepository;
        this.uploadBatchRepository = uploadBatchRepository;
        this.clamdService = clamdService;
        this.objectStorageService = objectStorageService;
        this.storageProperties = storageProperties;
        this.accountsService = accountsService;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.systemConfigService = systemConfigService;
    }

    /**
     * chunked-file-upload-02-personal-chunked-upload.md §2.3: additive
     * {@code chunkThresholdBytes} field so the personal page can decide
     * whole-file vs. chunked upload the same way {@code /ingest/limits} does
     * for the ingest page - there is no separate personal-scoped config key
     * (AC-1: shares the same {@code system_config} value as ingest).
     */
    public record QuotaInfo(long quotaBytes, long usedBytes, long remainingBytes, long chunkThresholdBytes) {
    }

    public record PersonalFileContent(PersonalFile file, ResponseInputStream<GetObjectResponse> stream) {
    }

    // ---------------------------------------------------------------- quota

    public QuotaInfo getQuota(KeycloakUser user) {
        long used = fileRepository.sumSizeByOwnerSub(user.sub());
        long quota = resolveQuotaBytes(user);
        long chunkThresholdBytes = systemConfigService.getLong(SystemConfigDefaults.UPLOAD_CHUNK_THRESHOLD_BYTES);
        return new QuotaInfo(quota, used, Math.max(0, quota - used), chunkThresholdBytes);
    }

    /**
     * personal-storage-quota.md §2: the sole source of the storage quota is
     * {@code account.storage_quota_bytes} (account-lifecycle.md), read/
     * lazily-backfilled through {@link AccountsService#getOrCreateAccountRow}
     * - no static YAML fallback, no per-role recompute here (Story 4 AC-3:
     * a role change does not retroactively change an existing account's
     * snapshotted quota).
     */
    private long resolveQuotaBytes(KeycloakUser user) {
        Account account = accountsService.getOrCreateAccountRow(user.sub(), assignableRolesOf(user));
        return account.getStorageQuotaBytes();
    }

    /** Bare Keycloak role names held by {@code user}, filtered to the assignable set (backfill quota calc only). */
    private static List<String> assignableRolesOf(KeycloakUser user) {
        List<String> roles = user.realmRoles() != null ? user.realmRoles() : List.of();
        return roles.stream().filter(AssignableRoles.ALL::contains).toList();
    }

    // -------------------------------------------------------------- folders

    /**
     * Owner-scoped tree (same in-memory assembly as WarehouseService.getTree).
     * {@code personal_folder} has no persisted depth column (out of this
     * feature's scope), so {@code FolderNode.depth} is computed from
     * recursion level here instead (1 = root), matching the warehouse tree's
     * semantics without a schema change.
     */
    public List<FolderNode> getTree(KeycloakUser user) {
        List<PersonalFolder> all = folderRepository.findAllByOwnerSubOrderByNameAsc(user.sub());
        Map<UUID, List<PersonalFolder>> childrenByParent = new HashMap<>();
        for (PersonalFolder folder : all) {
            childrenByParent.computeIfAbsent(folder.getParentId(), k -> new ArrayList<>()).add(folder);
        }
        return buildNodes(null, childrenByParent, 1);
    }

    private List<FolderNode> buildNodes(UUID parentId, Map<UUID, List<PersonalFolder>> childrenByParent, int depth) {
        List<PersonalFolder> children = childrenByParent.getOrDefault(parentId, List.of());
        List<FolderNode> nodes = new ArrayList<>(children.size());
        for (PersonalFolder child : children) {
            nodes.add(new FolderNode(
                    child.getId(), child.getName(), depth, buildNodes(child.getId(), childrenByParent, depth + 1)));
        }
        return nodes;
    }

    public PersonalFolder createFolder(KeycloakUser user, String name, UUID parentId) {
        if (parentId != null) {
            findOwnedFolder(user, parentId);
        }

        PersonalFolder folder = new PersonalFolder();
        folder.setOwnerSub(user.sub());
        folder.setOwnerUsername(user.preferredUsername());
        folder.setName(name);
        folder.setParentId(parentId);
        OffsetDateTime now = OffsetDateTime.now();
        folder.setCreatedAt(now);
        folder.setUpdatedAt(now);

        try {
            folderRepository.save(folder);
        } catch (DataIntegrityViolationException e) {
            if (SqlErrors.isUniqueViolation(e)) {
                throw new ConflictException("A folder with this name already exists at this level");
            }
            throw e;
        }

        auditFolder(user, "personal.folder.create", folder.getId(), metadata("name", folder.getName(), "parentId", parentId));
        return folder;
    }

    /** Rename and/or move. {@code parentIdProvided=false} leaves the location unchanged. */
    public PersonalFolder updateFolder(
            KeycloakUser user, UUID id, boolean nameProvided, String name, boolean parentIdProvided, UUID parentId) {
        PersonalFolder folder = findOwnedFolder(user, id);
        boolean renamed = nameProvided && !Objects.equals(name, folder.getName());
        boolean moved = parentIdProvided && !Objects.equals(parentId, folder.getParentId());

        if (moved) {
            if (parentId != null) {
                if (parentId.equals(id)) {
                    throw new UnprocessableEntityException("Cannot move a folder into itself");
                }
                findOwnedFolder(user, parentId);
                assertNotDescendant(user, id, parentId);
            }
            folder.setParentId(parentId);
        }
        if (nameProvided) {
            folder.setName(name);
        }
        folder.setUpdatedAt(OffsetDateTime.now());

        try {
            folderRepository.save(folder);
        } catch (DataIntegrityViolationException e) {
            if (SqlErrors.isUniqueViolation(e)) {
                throw new ConflictException("A folder with this name already exists at this level");
            }
            throw e;
        }

        Map<String, Object> meta = metadata("name", folder.getName(), "parentId", folder.getParentId());
        if (renamed && moved) {
            meta.put("alsoRenamed", true);
        }
        auditFolder(user, moved ? "personal.folder.move" : "personal.folder.rename", id, meta);
        return folder;
    }

    /**
     * Deleting a non-empty folder is blocked: both the child-folder self-FK
     * and personal_file.folder_id are ON DELETE RESTRICT, so the emptiness
     * check is the constraint itself.
     */
    public void deleteFolder(KeycloakUser user, UUID id) {
        findOwnedFolder(user, id);
        try {
            folderRepository.deleteById(id);
        } catch (DataIntegrityViolationException e) {
            if (SqlErrors.isForeignKeyViolation(e)) {
                throw new ConflictException("Folder still contains subfolders or files");
            }
            throw e;
        }
        auditFolder(user, "personal.folder.delete", id, null);
    }

    /** Moving a folder under its own descendant would detach the subtree. */
    private void assertNotDescendant(KeycloakUser user, UUID folderId, UUID candidateId) {
        UUID currentId = candidateId;
        while (currentId != null) {
            if (currentId.equals(folderId)) {
                throw new UnprocessableEntityException("Cannot move a folder into its own subtree");
            }
            currentId = folderRepository
                    .findByIdAndOwnerSub(currentId, user.sub())
                    .map(PersonalFolder::getParentId)
                    .orElse(null);
        }
    }

    private PersonalFolder findOwnedFolder(KeycloakUser user, UUID id) {
        return folderRepository.findByIdAndOwnerSub(id, user.sub())
                .orElseThrow(() -> new NotFoundException("Folder not found"));
    }

    // ----------------------------------------------------------------- batches

    /**
     * personal-storage-quota.md §4.3: early, unlocked quota pre-check at
     * batch-open time - a clear, up-front rejection before the client spends
     * time uploading; the authoritative check is still the locked re-check in
     * {@link #commitFileInsert} on every file.
     */
    public UploadBatch openBatch(KeycloakUser user, UUID folderId, int declaredFileCount, long declaredTotalBytes) {
        if (folderId != null) {
            findOwnedFolder(user, folderId);
        }

        long quotaBytes = resolveQuotaBytes(user);
        long used = fileRepository.sumSizeByOwnerSub(user.sub());
        if (declaredTotalBytes > quotaBytes - used) {
            throw quotaExceeded(quotaBytes, used, declaredTotalBytes);
        }

        UploadBatch batch = new UploadBatch();
        batch.setKind(BatchKind.PERSONAL);
        batch.setTargetFolderId(null);
        batch.setTargetPersonalFolderId(folderId);
        batch.setOwnerSub(user.sub());
        batch.setOwnerUsername(user.preferredUsername());
        batch.setDeclaredFileCount(declaredFileCount);
        batch.setDeclaredTotalBytes(declaredTotalBytes);
        batch.setStatus(UploadBatchStatus.OPEN);
        OffsetDateTime now = OffsetDateTime.now();
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        batch.setExpiresAt(now.plusHours(24));
        UploadBatch saved = uploadBatchRepository.save(batch);

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "personal.batch.open", "upload_batch",
                saved.getId().toString(), "success", auditRequestContext.method(), auditRequestContext.path(), 200,
                auditRequestContext.ip(),
                metadata(
                        "folderId", folderId,
                        "declaredFileCount", declaredFileCount,
                        "declaredTotalBytes", declaredTotalBytes)));
        return saved;
    }

    /** GET /personal/batches/{id} - reflects lazy expiry without throwing (mirrors data-ingestion.md's getStatus). */
    public UploadBatch getBatch(KeycloakUser user, UUID batchId) {
        UploadBatch batch = findOwnedBatch(user, batchId);
        lazilyExpireIfNeeded(batch);
        return batch;
    }

    /** POST /personal/batches/{id}/close - optional convenience, never required (§3), idempotent. */
    public UploadBatch closeBatch(KeycloakUser user, UUID batchId) {
        UploadBatch batch = findOwnedBatch(user, batchId);
        if (UploadBatchStatus.OPEN.equals(batch.getStatus())) {
            batch.setStatus(UploadBatchStatus.CLOSED);
            batch.setUpdatedAt(OffsetDateTime.now());
            uploadBatchRepository.save(batch);

            auditService.recordAsync(new AuditService.AuditEntry(
                    user.preferredUsername(), user.sub(), "personal.batch.close", "upload_batch",
                    batch.getId().toString(), "success", auditRequestContext.method(), auditRequestContext.path(),
                    200, auditRequestContext.ip(),
                    metadata(
                            "acceptedFileCount", batch.getAcceptedFileCount(),
                            "skippedFileCount", batch.getSkippedFileCount(),
                            "rejectedFileCount", batch.getRejectedFileCount())));
        }
        return batch;
    }

    /**
     * personal-storage-quota.md §4.1: owner-scoped AND kind-scoped - a
     * warehouse batch id (even one owned by this same user) is "not found"
     * here, and vice versa for /ingest/**. Never leaks existence (404, not 403).
     */
    private UploadBatch findOwnedBatch(KeycloakUser user, UUID batchId) {
        return uploadBatchRepository.findByIdAndOwnerSubAndKind(batchId, user.sub(), BatchKind.PERSONAL)
                .orElseThrow(() -> new NotFoundException("Batch not found"));
    }

    /**
     * chunked-file-upload-02-personal-chunked-upload.md §4.1: public wrapper
     * around {@link #findOwnedOpenBatch} so {@code UploadSessionService}
     * (package {@code upload}, not {@code personal}) can reuse the exact same
     * owner-scoped + kind-scoped + lazy-expire batch lookup that
     * {@link #uploadFileInBatch} uses, instead of re-deriving it. Delegates
     * unchanged - no new logic here.
     */
    public UploadBatch loadOwnedOpenBatch(KeycloakUser user, UUID batchId) {
        return findOwnedOpenBatch(user, batchId);
    }

    /**
     * chunked-file-upload-02-personal-chunked-upload.md §4.1: public wrapper
     * around {@link #findOwnedFolder}, used by
     * {@code UploadSessionService.initPersonal} to fail fast (before any byte
     * is received) when {@code folderId} does not belong to the caller.
     * {@link #uploadFileInBatch} still re-checks the folder at {@code complete}
     * time on its own (unchanged) - this is a deliberate, harmless double-check,
     * not a duplicate source of truth.
     */
    public void assertOwnedFolder(KeycloakUser user, UUID folderId) {
        findOwnedFolder(user, folderId);
    }

    /** §4.2 step 1: load a batch for receiving a file, throwing for every non-OPEN outcome. */
    private UploadBatch findOwnedOpenBatch(KeycloakUser user, UUID batchId) {
        UploadBatch batch = findOwnedBatch(user, batchId);
        lazilyExpireIfNeeded(batch);
        if (UploadBatchStatus.OPEN.equals(batch.getStatus())) {
            return batch;
        }
        if (UploadBatchStatus.EXPIRED.equals(batch.getStatus())) {
            throw batchExpired();
        }
        throw batchClosed();
    }

    private void lazilyExpireIfNeeded(UploadBatch batch) {
        if (UploadBatchStatus.OPEN.equals(batch.getStatus()) && OffsetDateTime.now().isAfter(batch.getExpiresAt())) {
            batch.setStatus(UploadBatchStatus.EXPIRED);
            batch.setUpdatedAt(OffsetDateTime.now());
            uploadBatchRepository.save(batch);
        }
    }

    // ----------------------------------------------------------------- files

    /**
     * personal-storage-quota.md §4.2: the FR-12 safety pipeline (fail-closed
     * scan only - NOT format/allowlist, Story 1 AC-2, bug fix over the old
     * single-shot {@code uploadFile}) followed by quota-checked storage
     * inside an already-open batch. Failures persist no row - personal files
     * aren't evidence, the audit event is the only trace - and the bytes
     * land in the hard-deletable personal bucket under {@code <sub>/<uuid>}.
     */
    public PersonalFile uploadFileInBatch(KeycloakUser user, UUID batchId, UUID folderId, MultipartFile file) {
        UploadBatch batch = findOwnedOpenBatch(user, batchId);

        if (folderId != null) {
            findOwnedFolder(user, folderId);
        }

        // §4.2 step 2b (Quyết định 2 mục 0): per-file size cap, shared with
        // data-ingestion.md's ingest.max_file_size_bytes (both pipelines are
        // bounded by the same ClamdService capability) - checked BEFORE
        // ClamAV on purpose: scanning is the most expensive step, don't spend
        // it on a file that is certain to be rejected.
        long maxFileSizeBytes = systemConfigService.getLong(SystemConfigDefaults.INGEST_MAX_FILE_SIZE_BYTES);
        if (file.getSize() > maxFileSizeBytes) {
            throw fileTooLarge(maxFileSizeBytes, file.getSize());
        }

        // Fail closed before doing any work (FR-12): no scanner, no upload.
        if (!clamdService.ping()) {
            throw scannerUnavailable();
        }

        Path tempFile = bufferToTempFile(file);
        try {
            String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unnamed";
            String mimeType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
            String sha256 = sha256Hex(tempFile);
            long size = fileSize(tempFile);

            // §4.2 step 3 (Story 1 AC-2): no format/allowlist check at all for
            // personal storage - deliberately no call to MimeAllowlist/AllowlistService.

            ClamdService.ScanVerdict verdict = scan(tempFile);
            if (!verdict.clean()) {
                String reason = "malware detected (" + verdict.signature() + ")";
                auditReject(user, originalName, sha256, size, reason);
                throw malwareDetected("File rejected: " + reason);
            }

            String storageKey = user.sub() + "/" + UUID.randomUUID();
            objectStorageService.putObject(storageKey, tempFile.toFile(), mimeType, storageProperties.personalBucket());

            PersonalFile saved;
            try {
                saved = commitFileInsert(user, batch.getId(), folderId, originalName, mimeType, sha256, storageKey, size);
            } catch (RuntimeException e) {
                // The object was already stored; compensate so it can't orphan. A
                // crash between put and commit can still orphan one - logged debt,
                // reconciliation sweep later (see spec §7 risk note).
                try {
                    objectStorageService.deleteObject(storageKey, storageProperties.personalBucket());
                } catch (Exception cleanupError) {
                    log.error("Orphaned personal object '{}': {}", storageKey, cleanupError.toString());
                }
                throw e;
            }

            auditService.recordAsync(new AuditService.AuditEntry(
                    user.preferredUsername(), user.sub(), "personal.file.create", "personal_file",
                    saved.getId().toString(), "success", auditRequestContext.method(), auditRequestContext.path(),
                    200, auditRequestContext.ip(),
                    metadata("sha256", sha256, "folderId", folderId, "batchId", batchId, "size", saved.getSize())));
            return saved;
        } finally {
            deleteQuietly(tempFile);
        }
    }

    /**
     * Authoritative quota commit (§4.2 step 7, §4.5): serialize per user with
     * an advisory xact lock covering BOTH the SUM re-check and the insert in
     * one transaction, then bump the batch's display counters - DB usage can
     * never exceed the quota, across every batch of the same owner
     * concurrently (unlike data-ingestion.md's per-batch {@code UPDATE ...
     * RETURNING}, which does not need cross-batch serialization - see spec
     * §1/§4.5 for why the two pipelines deliberately differ here).
     */
    private PersonalFile commitFileInsert(
            KeycloakUser user, UUID batchId, UUID folderId, String originalName, String mimeType, String sha256,
            String storageKey, long size) {
        return transactionTemplate.execute(status -> {
            entityManager.unwrap(Session.class).doWork(connection -> {
                try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")) {
                    statement.setString(1, user.sub());
                    statement.execute();
                }
            });

            long used = fileRepository.sumSizeByOwnerSub(user.sub());
            long quotaBytes = resolveQuotaBytes(user);
            if (used + size > quotaBytes) {
                throw quotaExceeded(quotaBytes, used, size);
            }

            PersonalFile file = new PersonalFile();
            file.setOwnerSub(user.sub());
            file.setOwnerUsername(user.preferredUsername());
            file.setFolderId(folderId);
            file.setOriginalName(originalName);
            file.setMimeType(mimeType);
            file.setSize(size);
            file.setSha256(sha256);
            file.setStorageKey(storageKey);
            file.setBatchId(batchId);
            file.setCreatedAt(OffsetDateTime.now());
            PersonalFile saved = fileRepository.save(file);

            // Display-only progress counters (Story 3 AC-1/AC-3) - NOT the quota
            // enforcement mechanism (that's the SUM re-check above, under the
            // same advisory lock).
            uploadBatchRepository.incrementActual(batchId, size);

            return saved;
        });
    }

    /** All of the owner's files (bounded by the quota; client groups by folder). */
    public List<PersonalFile> listFiles(KeycloakUser user) {
        return fileRepository.findByOwnerSubOrderByCreatedAtDesc(user.sub());
    }

    /** FR-14: move a file between folders (null = root). */
    public PersonalFile moveFile(KeycloakUser user, UUID id, UUID folderId) {
        PersonalFile file = findOwnedFile(user, id);
        if (folderId != null) {
            findOwnedFolder(user, folderId);
        }
        file.setFolderId(folderId);
        fileRepository.save(file);

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "personal.file.move", "personal_file", file.getId().toString(),
                "success", auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                metadata("folderId", folderId)));
        return file;
    }

    /** Streams the stored file; records the access (FR-09 covers reads too). */
    public PersonalFileContent getContent(KeycloakUser user, UUID id) {
        PersonalFile file = findOwnedFile(user, id);
        var stream = objectStorageService.getObject(file.getStorageKey(), storageProperties.personalBucket());

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "personal.file.download", "personal_file",
                file.getId().toString(), "success", auditRequestContext.method(), auditRequestContext.path(), 200,
                auditRequestContext.ip(), null));
        return new PersonalFileContent(file, stream);
    }

    /**
     * FR-14 hard delete (personal storage is not evidence): remove the row
     * first - that's what frees quota - then the object, best-effort.
     */
    public void deleteFile(KeycloakUser user, UUID id) {
        PersonalFile file = findOwnedFile(user, id);
        fileRepository.delete(file);
        try {
            objectStorageService.deleteObject(file.getStorageKey(), storageProperties.personalBucket());
        } catch (Exception e) {
            log.error("Orphaned personal object '{}': {}", file.getStorageKey(), e.toString());
        }

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "personal.file.delete", "personal_file", id.toString(),
                "success", auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                metadata("size", file.getSize())));
    }

    private PersonalFile findOwnedFile(KeycloakUser user, UUID id) {
        return fileRepository.findByIdAndOwnerSub(id, user.sub())
                .orElseThrow(() -> new NotFoundException("File not found"));
    }

    /** Rejected uploads persist no row; the audit event is the only trace. */
    private void auditReject(KeycloakUser user, String originalName, String sha256, long size, String reason) {
        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "personal.file.reject", "personal_file", null, "failure",
                auditRequestContext.method(), auditRequestContext.path(), null, auditRequestContext.ip(),
                metadata("originalName", originalName, "sha256", sha256, "size", size, "reason", reason)));
    }

    private void auditFolder(KeycloakUser user, String action, UUID folderId, Map<String, Object> metadata) {
        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), action, "personal_folder", folderId.toString(), "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(), metadata));
    }

    /** Builds a metadata map allowing null values (unlike {@code Map.of}). */
    private static Map<String, Object> metadata(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private Path bufferToTempFile(MultipartFile file) {
        try {
            Path tempFile = Files.createTempFile("upload-", ".tmp");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return tempFile;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to buffer upload to a temp file", e);
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

    // ---- error bodies (match spec §3 API contract: {code, message}) -----

    private static ApiException quotaExceeded(long quota, long usedBytes, long incoming) {
        long remaining = Math.max(0, quota - usedBytes);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "QUOTA_EXCEEDED");
        body.put(
                "message",
                "Vượt hạn mức lưu trữ cá nhân: đã dùng " + usedBytes + " / " + quota + " byte, còn trống "
                        + remaining + " byte, cần " + incoming + " byte");
        body.put("usedBytes", usedBytes);
        body.put("quotaBytes", quota);
        body.put("remainingBytes", remaining);
        return new ConflictException(body, "Storage quota exceeded");
    }

    private static ApiException batchClosed() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "BATCH_CLOSED");
        body.put("message", "Lượt tải đã đóng");
        return new ConflictException(body, "Batch closed");
    }

    private static ApiException batchExpired() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "BATCH_EXPIRED");
        body.put("message", "Lượt tải đã hết hạn");
        return new ConflictException(body, "Batch expired");
    }

    private static ApiException fileTooLarge(long maxBytes, long actualBytes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "FILE_TOO_LARGE");
        body.put("message", "Tệp vượt quá dung lượng tối đa cho phép (" + maxBytes + " byte)");
        body.put("maxBytes", maxBytes);
        body.put("actualBytes", actualBytes);
        return new UnprocessableEntityException(body, "File too large");
    }

    private static ApiException malwareDetected(String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "MALWARE_DETECTED");
        body.put("message", reason);
        return new UnprocessableEntityException(body, reason);
    }

    private static ApiException scannerUnavailable() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "SCANNER_UNAVAILABLE");
        body.put("message", "Không thể kết nối trình quét mã độc");
        return new ServiceUnavailableException(body, "Malware scanner unavailable");
    }
}
