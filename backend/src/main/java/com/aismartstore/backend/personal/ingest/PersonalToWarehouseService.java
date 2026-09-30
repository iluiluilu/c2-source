package com.aismartstore.backend.personal.ingest;

import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.common.BadRequestException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.common.ServiceUnavailableException;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.ingest.BatchKind;
import com.aismartstore.backend.ingest.UploadBatch;
import com.aismartstore.backend.ingest.UploadBatchRepository;
import com.aismartstore.backend.ingest.UploadBatchService;
import com.aismartstore.backend.ingest.UploadBatchStatus;
import com.aismartstore.backend.personal.PersonalFile;
import com.aismartstore.backend.personal.PersonalFileRepository;
import com.aismartstore.backend.personal.PersonalFolder;
import com.aismartstore.backend.personal.PersonalFolderRepository;
import com.aismartstore.backend.scanning.AllowlistService;
import com.aismartstore.backend.warehouse.WarehouseCapacityService;
import com.aismartstore.backend.warehouse.WarehouseService;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * personal-to-warehouse.md §4: validates + collects the selection, creates the
 * warehouse {@code upload_batch} with one {@code personal_ingest_item} per file,
 * then hands the run to {@link PersonalIngestWorker}. Not itself transactional:
 * only the batch/item creation is wrapped (so the worker starts after commit).
 */
@Service
public class PersonalToWarehouseService {

    private final PersonalFileRepository fileRepository;
    private final PersonalFolderRepository folderRepository;
    private final PersonalIngestItemRepository itemRepository;
    private final UploadBatchRepository uploadBatchRepository;
    private final UploadBatchService uploadBatchService;
    private final WarehouseService warehouseService;
    private final WarehouseCapacityService warehouseCapacityService;
    private final AllowlistService allowlistService;
    private final DocumentRepository documentRepository;
    private final AuditService auditService;
    private final PersonalIngestWorker worker;
    private final TransactionTemplate transactionTemplate;
    private final int itemRetentionDays;

    public PersonalToWarehouseService(
            PersonalFileRepository fileRepository,
            PersonalFolderRepository folderRepository,
            PersonalIngestItemRepository itemRepository,
            UploadBatchRepository uploadBatchRepository,
            UploadBatchService uploadBatchService,
            WarehouseService warehouseService,
            WarehouseCapacityService warehouseCapacityService,
            AllowlistService allowlistService,
            DocumentRepository documentRepository,
            AuditService auditService,
            PersonalIngestWorker worker,
            PlatformTransactionManager transactionManager,
            @Value("${personal-ingest.item-retention-days:30}") int itemRetentionDays) {
        this.fileRepository = fileRepository;
        this.folderRepository = folderRepository;
        this.itemRepository = itemRepository;
        this.uploadBatchRepository = uploadBatchRepository;
        this.uploadBatchService = uploadBatchService;
        this.warehouseService = warehouseService;
        this.warehouseCapacityService = warehouseCapacityService;
        this.allowlistService = allowlistService;
        this.documentRepository = documentRepository;
        this.auditService = auditService;
        this.worker = worker;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.itemRetentionDays = itemRetentionDays;
    }

    public record Ineligible(UUID personalFileId, String name, String code) {
    }

    public record StartResult(UUID batchId, int total, List<Ineligible> ineligible, OffsetDateTime expiresAt) {
    }

    public record ItemView(
            UUID personalFileId, String name, String status, String code, UUID documentId, List<UUID> duplicateOf) {
    }

    public record BatchResultView(UUID batchId, String status, int done, int total, List<ItemView> items) {
    }

    /** A file picked up from the selection, with the warehouse relative path it will get. */
    record Collected(PersonalFile file, String relativePath) {
    }

    // ----------------------------------------------------------------- start

    public StartResult start(KeycloakUser user, FromPersonalDto dto) {
        List<UUID> fileIds = dto.fileIdsOrEmpty();
        List<UUID> folderIds = dto.folderIdsOrEmpty();
        if (fileIds.isEmpty() && folderIds.isEmpty()) {
            throw new BadRequestException("fileIds or folderIds must not be empty");
        }

        // AC-6: only an already existing warehouse folder is a valid target.
        if (!warehouseService.folderExists(dto.targetFolderId())) {
            throw new NotFoundException("Folder not found");
        }

        List<Collected> collected = collect(user, fileIds, folderIds);
        if (collected.isEmpty()) {
            throw new BadRequestException("No files found in the selection");
        }

        // Server-side filter (AC-8): not counted against the batch limits.
        UploadBatchService.Limits limits = uploadBatchService.getLimits();
        List<Collected> eligible = new ArrayList<>();
        Map<UUID, String> ineligibleCodes = new LinkedHashMap<>();
        long eligibleBytes = 0;
        for (Collected c : collected) {
            PersonalFile f = c.file();
            if (!allowlistService.isAllowedByNameAndMime(f.getOriginalName(), f.getMimeType())) {
                ineligibleCodes.put(f.getId(), "INVALID_FORMAT");
            } else if (f.getSize() > limits.maxFileSizeBytes()) {
                ineligibleCodes.put(f.getId(), "FILE_TOO_LARGE");
            } else {
                eligible.add(c);
                eligibleBytes += f.getSize();
            }
        }

        if (eligible.size() > limits.maxFilesPerBatch() || eligibleBytes > limits.maxBatchSizeBytes()) {
            throw batchLimitExceeded();
        }
        if (eligible.isEmpty()) {
            // upload_batch.declared_file_count must be > 0 (V10), so a run with nothing to ingest has no batch.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "NO_ELIGIBLE_FILES");
            body.put("message", "Không có tệp nào đủ điều kiện để bổ sung vào kho");
            body.put("ineligible", collected.stream()
                    .map(c -> new Ineligible(
                            c.file().getId(), c.file().getOriginalName(), ineligibleCodes.get(c.file().getId())))
                    .toList());
            throw new ApiException(HttpStatus.BAD_REQUEST, body, "No eligible files");
        }

        warehouseCapacityService.assertIngestAllowed();

        int eligibleCount = eligible.size();
        long declaredBytes = eligibleBytes;
        UploadBatch batch = transactionTemplate.execute(status -> {
            UploadBatch b = new UploadBatch();
            b.setKind(BatchKind.WAREHOUSE);
            b.setTargetFolderId(dto.targetFolderId());
            b.setOwnerSub(user.sub());
            b.setOwnerUsername(user.preferredUsername());
            b.setDeclaredFileCount(eligibleCount);
            b.setDeclaredTotalBytes(declaredBytes);
            b.setStatus(UploadBatchStatus.OPEN);
            OffsetDateTime now = OffsetDateTime.now();
            b.setCreatedAt(now);
            b.setUpdatedAt(now);
            b.setExpiresAt(now.plusHours(24));
            uploadBatchRepository.save(b);

            List<PersonalIngestItem> items = new ArrayList<>();
            for (Collected c : collected) {
                PersonalIngestItem item = new PersonalIngestItem();
                item.setBatchId(b.getId());
                item.setPersonalFileId(c.file().getId());
                item.setOriginalName(c.file().getOriginalName());
                item.setSize(c.file().getSize());
                item.setRelativePath(c.relativePath());
                String code = ineligibleCodes.get(c.file().getId());
                if (code != null) {
                    item.setStatus(PersonalIngestItem.FAILED);
                    item.setCode(code);
                    item.setProcessedAt(now);
                }
                items.add(item);
            }
            itemRepository.saveAll(items);
            return b;
        });

        List<Ineligible> ineligible = new ArrayList<>();
        for (Collected c : collected) {
            String code = ineligibleCodes.get(c.file().getId());
            if (code != null) {
                ineligible.add(new Ineligible(c.file().getId(), c.file().getOriginalName(), code));
                auditIneligible(user, dto.targetFolderId(), c.file(), code);
            }
        }

        try {
            worker.run(user, batch.getId(), dto.targetFolderId(), dto.note());
        } catch (TaskRejectedException e) {
            failPendingAndClose(batch.getId(), "SERVER_BUSY");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "SERVER_BUSY");
            body.put("message", "Máy chủ đang bận, vui lòng thử lại sau");
            throw new ServiceUnavailableException(body, "Personal ingest executor saturated");
        }

        return new StartResult(batch.getId(), collected.size(), ineligible, batch.getExpiresAt());
    }

    private void failPendingAndClose(UUID batchId, String code) {
        List<PersonalIngestItem> pending = itemRepository.findByBatchIdOrderByOriginalNameAsc(batchId).stream()
                .filter(i -> PersonalIngestItem.PENDING.equals(i.getStatus()))
                .toList();
        for (PersonalIngestItem item : pending) {
            item.setStatus(PersonalIngestItem.FAILED);
            item.setCode(code);
            item.setProcessedAt(OffsetDateTime.now());
        }
        itemRepository.saveAll(pending);
        uploadBatchService.close(batchId);
    }

    private void auditIneligible(KeycloakUser user, UUID targetFolderId, PersonalFile file, String code) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourcePersonalFileId", file.getId().toString());
        metadata.put("originalName", file.getOriginalName());
        metadata.put("size", file.getSize());
        metadata.put("targetFolderId", targetFolderId.toString());
        metadata.put("result", PersonalIngestItem.FAILED);
        metadata.put("code", code);
        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "ingest.from_personal.file", "personal_file",
                file.getId().toString(), "failure", null, null, null, null, metadata));
    }

    // --------------------------------------------------------------- collect

    /**
     * Resolves the selection to files. Foreign or unknown ids both produce the
     * same 404 (AC-2). A selected folder contributes its whole subtree; the
     * relative path includes the selected folder's own name (decision #1):
     * select {@code A} containing {@code B/x.pdf} -> {@code A/B}; a file
     * directly in {@code A} -> {@code A}; a hand-picked file -> {@code ""}.
     */
    List<Collected> collect(KeycloakUser user, List<UUID> fileIds, List<UUID> folderIds) {
        Map<UUID, Collected> result = new LinkedHashMap<>();

        for (UUID id : fileIds) {
            PersonalFile f = fileRepository
                    .findByIdAndOwnerSub(id, user.sub())
                    .orElseThrow(PersonalToWarehouseService::selectionNotFound);
            result.putIfAbsent(f.getId(), new Collected(f, ""));
        }

        if (!folderIds.isEmpty()) {
            for (UUID id : folderIds) {
                folderRepository.findByIdAndOwnerSub(id, user.sub()).orElseThrow(
                        PersonalToWarehouseService::selectionNotFound);
            }
            List<PersonalFolder> all = folderRepository.findAllByOwnerSubOrderByNameAsc(user.sub());
            Map<UUID, PersonalFolder> byId = new HashMap<>();
            Map<UUID, List<PersonalFolder>> children = new HashMap<>();
            for (PersonalFolder folder : all) {
                byId.put(folder.getId(), folder);
                if (folder.getParentId() != null) {
                    children.computeIfAbsent(folder.getParentId(), k -> new ArrayList<>()).add(folder);
                }
            }

            // folder id -> path, for every folder in the selected subtrees (first selection wins).
            Map<UUID, String> pathByFolder = new LinkedHashMap<>();
            Set<UUID> visited = new HashSet<>();
            for (UUID rootId : folderIds) {
                Deque<UUID> stack = new ArrayDeque<>();
                if (visited.add(rootId)) {
                    pathByFolder.put(rootId, byId.get(rootId).getName());
                    stack.push(rootId);
                }
                while (!stack.isEmpty()) {
                    UUID current = stack.pop();
                    for (PersonalFolder child : children.getOrDefault(current, List.of())) {
                        if (visited.add(child.getId())) {
                            pathByFolder.put(child.getId(), pathByFolder.get(current) + "/" + child.getName());
                            stack.push(child.getId());
                        }
                    }
                }
            }

            List<PersonalFile> files = fileRepository.findByOwnerSubAndFolderIdIn(user.sub(), pathByFolder.keySet());
            files.stream()
                    .sorted((a, b) -> a.getOriginalName().compareToIgnoreCase(b.getOriginalName()))
                    .forEach(f -> result.putIfAbsent(f.getId(), new Collected(f, pathByFolder.get(f.getFolderId()))));
        }
        return new ArrayList<>(result.values());
    }

    // ---------------------------------------------------------------- status

    public BatchResultView getResult(KeycloakUser user, UUID batchId) {
        UploadBatch batch = uploadBatchRepository
                .findByIdAndOwnerSubAndKind(batchId, user.sub(), BatchKind.WAREHOUSE)
                .orElseThrow(PersonalToWarehouseService::batchNotFound);
        List<PersonalIngestItem> items = itemRepository.findByBatchIdOrderByOriginalNameAsc(batchId);
        if (items.isEmpty()) {
            throw batchNotFound(); // e.g. an ordinary /ingest batch, or one whose items were cleaned up
        }
        // lazily reflect expiry, same as GET /ingest/batches/{id}
        batch = uploadBatchService.getStatus(batchId);

        int done = (int) items.stream().filter(i -> !PersonalIngestItem.PENDING.equals(i.getStatus())).count();
        List<ItemView> views = items.stream()
                .map(i -> new ItemView(
                        i.getPersonalFileId(),
                        i.getOriginalName(),
                        i.getStatus(),
                        i.getCode(),
                        i.getDocumentId(),
                        i.getDuplicateOf() == null ? List.of() : List.of(i.getDuplicateOf())))
                .toList();
        String status = UploadBatchStatus.OPEN.equals(batch.getStatus()) ? "OPEN" : "CLOSED";
        return new BatchResultView(batchId, status, done, items.size(), views);
    }

    // ------------------------------------------------------------ cleanup

    /** §4 step 4: daily retention of per-file results. Returns the number of rows deleted. */
    @Transactional
    public int purgeOldItems(OffsetDateTime now) {
        OffsetDateTime cutoff = now.minusDays(itemRetentionDays);
        return itemRepository.deleteForFinishedBatchesBefore(cutoff)
                + itemRepository.deleteForExpiredOpenBatchesBefore(cutoff);
    }

    // ------------------------------------------------- AC-21: personal list flag

    /** Ids of the caller's personal files that have a live (non-trashed) warehouse copy. */
    public Set<UUID> addedToWarehouseIds(KeycloakUser user) {
        return new HashSet<>(documentRepository.findLiveSourcePersonalFileIdsByUploader(user.sub()));
    }

    // ---------------------------------------------------------------- errors

    private static NotFoundException selectionNotFound() {
        return new NotFoundException("Personal file or folder not found");
    }

    private static ApiException batchNotFound() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "BATCH_NOT_FOUND");
        body.put("message", "Không tìm thấy lượt bổ sung");
        return new ApiException(HttpStatus.NOT_FOUND, body, "Batch not found");
    }

    /** 400 per the story (the older /ingest flow answers 409 - deliberately not reused). */
    private static ApiException batchLimitExceeded() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "BATCH_LIMIT_EXCEEDED");
        body.put("message", "Lượt bổ sung vượt quá hạn mức cho phép");
        return new ApiException(HttpStatus.BAD_REQUEST, body, "Batch limit exceeded");
    }
}
