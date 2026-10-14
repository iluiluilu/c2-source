package com.aismartstore.backend.warehouse;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.common.ConflictException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.common.SqlErrors;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.outbox.OutboxEvent;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Java port of the NestJS backend's warehouse/warehouse.service.ts, extended
 * per docs/03-dev/specs/warehouse-structure.md §4.1-4.3 with a materialized
 * path (max depth 5), branch move and an emptiness check that only counts
 * active ({@code status='clean'}, not soft-deleted) documents.
 */
@Service
public class WarehouseService {

    private static final int MAX_DEPTH = 5;

    private final WarehouseFolderRepository repository;
    private final DocumentRepository documentRepository;
    private final OutboxEventRepository outboxEventRepository;

    public WarehouseService(
            WarehouseFolderRepository repository,
            DocumentRepository documentRepository,
            OutboxEventRepository outboxEventRepository) {
        this.repository = repository;
        this.documentRepository = documentRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    // ------------------------------------------------------------- create/rename (Story 1)

    public WarehouseFolder createFolder(KeycloakUser user, String name, UUID parentId) {
        UUID[] pathIds;
        short depth;
        if (parentId != null) {
            WarehouseFolder parent =
                    repository.findById(parentId).orElseThrow(() -> new NotFoundException("Không tìm thấy thư mục cha"));
            if (parent.getDepth() + 1 > MAX_DEPTH) {
                throw maxDepthExceeded();
            }
            pathIds = appendToPath(parent.getPathIds(), parent.getId());
            depth = (short) (parent.getDepth() + 1);
        } else {
            pathIds = new UUID[0];
            depth = 1;
        }

        WarehouseFolder folder = new WarehouseFolder();
        folder.setName(name);
        folder.setParentId(parentId);
        folder.setPathIds(pathIds);
        folder.setDepth(depth);
        OffsetDateTime now = OffsetDateTime.now();
        folder.setCreatedAt(now);
        folder.setUpdatedAt(now);
        folder.setCreatedBySub(user.sub());
        folder.setCreatedByUsername(user.preferredUsername());
        folder.setUpdatedBySub(user.sub());
        folder.setUpdatedByUsername(user.preferredUsername());

        try {
            return repository.save(folder);
        } catch (DataIntegrityViolationException e) {
            if (SqlErrors.isUniqueViolation(e)) {
                throw new ConflictException("Đã có thư mục cùng tên ở cấp này");
            }
            throw e;
        }
    }

    /** Renames only: {@code path_ids}/{@code depth}/{@code parent_id} of this or any other row are untouched (AC-5). */
    public WarehouseFolder renameFolder(KeycloakUser user, UUID id, String name) {
        WarehouseFolder folder = repository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy thư mục"));
        folder.setName(name);
        folder.setUpdatedAt(OffsetDateTime.now());
        folder.setUpdatedBySub(user.sub());
        folder.setUpdatedByUsername(user.preferredUsername());

        try {
            return repository.save(folder);
        } catch (DataIntegrityViolationException e) {
            if (SqlErrors.isUniqueViolation(e)) {
                throw new ConflictException("Đã có thư mục cùng tên ở cấp này");
            }
            throw e;
        }
    }

    // ------------------------------------------------------------- move (Story 2 AC-2/AC-4)

    /**
     * Moves {@code id} under {@code newParentId} ({@code null} = root),
     * recomputing {@code path_ids}/{@code depth} for the folder AND its
     * whole subtree in one transaction (spec §4.2).
     * {@code document.folder_id} is never touched (AC-3).
     */
    @Transactional
    public WarehouseFolder moveFolder(KeycloakUser user, UUID id, UUID newParentId) {
        WarehouseFolder folder = repository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy thư mục"));

        WarehouseFolder newParent = null;
        if (newParentId != null) {
            newParent = repository
                    .findById(newParentId)
                    .orElseThrow(() -> new NotFoundException("Không tìm thấy thư mục cha đích"));
        }

        if (newParentId != null
                && (newParentId.equals(folder.getId()) || containsId(newParent.getPathIds(), folder.getId()))) {
            throw cycleDetected();
        }

        UUID[] newPrefix = newParent != null ? appendToPath(newParent.getPathIds(), newParent.getId()) : new UUID[0];
        int newDepth = newPrefix.length + 1;

        List<WarehouseFolder> descendants = repository.findDescendants(folder.getId());
        int maxDepthInSubtreeOld = folder.getDepth();
        for (WarehouseFolder d : descendants) {
            maxDepthInSubtreeOld = Math.max(maxDepthInSubtreeOld, d.getDepth());
        }
        int deltaDepth = newDepth - folder.getDepth();
        if (maxDepthInSubtreeOld + deltaDepth > MAX_DEPTH) {
            throw maxDepthExceeded();
        }

        int oldFolderPathLen = folder.getPathIds().length;
        OffsetDateTime now = OffsetDateTime.now();

        folder.setParentId(newParentId);
        folder.setPathIds(newPrefix);
        folder.setDepth((short) newDepth);
        folder.setUpdatedAt(now);
        folder.setUpdatedBySub(user.sub());
        folder.setUpdatedByUsername(user.preferredUsername());

        try {
            repository.save(folder);
        } catch (DataIntegrityViolationException e) {
            if (SqlErrors.isUniqueViolation(e)) {
                throw new ConflictException("Đã có thư mục cùng tên ở cấp này");
            }
            throw e;
        }

        if (!descendants.isEmpty()) {
            for (WarehouseFolder d : descendants) {
                UUID[] oldPath = d.getPathIds();
                // Keep the "tail" of d's old path AFTER folder.id (everything between folder and d);
                // replace the "head" (root..folder) with folder's brand-new path (spec §4.2 formula).
                UUID[] tail = Arrays.copyOfRange(oldPath, oldFolderPathLen + 1, oldPath.length);
                UUID[] newPath = new UUID[newPrefix.length + 1 + tail.length];
                System.arraycopy(newPrefix, 0, newPath, 0, newPrefix.length);
                newPath[newPrefix.length] = folder.getId();
                System.arraycopy(tail, 0, newPath, newPrefix.length + 1, tail.length);

                d.setPathIds(newPath);
                d.setDepth((short) (d.getDepth() + deltaDepth));
                d.setUpdatedAt(now);
            }
            repository.saveAll(descendants);
        }

        writeFolderMovedOutboxEvent(folder.getId(), now, user.sub());
        return folder;
    }

    /**
     * SPEC-search-semantic T10 (Q-3, Q-10): same transaction as the {@code path_ids} update above, so
     * the search index can never be told about a move that was rolled back (nor miss one that
     * committed). The payload deliberately carries only the folder id: {@code SearchIndexOutboxPoller}
     * recomputes {@code folder_path_ids} for the whole subtree from PostgreSQL.
     */
    private void writeFolderMovedOutboxEvent(UUID folderId, OffsetDateTime movedAt, String movedBySub) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType("folder.moved");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("folderId", folderId.toString());
        payload.put("movedAt", movedAt.toString());
        payload.put("movedBySub", movedBySub);
        event.setPayload(payload);
        event.setCreatedAt(movedAt);
        outboxEventRepository.save(event);
    }

    // ------------------------------------------------------------- delete (Story 3)

    /**
     * Blocked only by an ACTIVE document (status='clean', not soft-deleted)
     * or a child folder (AC-1/AC-2). Documents that are rejected/infected/
     * soft-deleted are detached ({@code folder_id = NULL} via the FK's
     * {@code ON DELETE SET NULL}), never block deletion.
     */
    @Transactional
    public void deleteFolder(UUID id) {
        WarehouseFolder folder = repository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy thư mục"));
        if (repository.existsByParentId(id)) {
            throw folderNotEmpty("hasChildFolders");
        }
        if (documentRepository.existsByFolderIdAndStatusAndDeletedAtIsNull(id, DocumentStatus.CLEAN)) {
            throw folderNotEmpty("hasActiveDocuments");
        }

        snapshotDeletedFolderPathIds(folder);

        try {
            repository.deleteById(id);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("Không tìm thấy thư mục");
        } catch (DataIntegrityViolationException e) {
            if (SqlErrors.isForeignKeyViolation(e)) {
                throw folderNotEmpty("hasChildFolders");
            }
            throw e;
        }
    }

    /**
     * delete-restore-trash-02-xoa-cung-xung-dot.md §4.4: right before {@code
     * folder} disappears, snapshot its {@code path_ids ++ [id]} (root ->
     * leaf) onto every document still filed here - ANY status (soft-deleted/
     * rejected/infected too - a rejected/infected row can still carry a
     * {@code folder_id} since only ACTIVE documents block deletion above).
     * Restore's "nearest still-existing ancestor" suggestion (§4.4) needs
     * this taken now, not reconstructed later from the (by-then-gone) folder
     * row. Never overwrites an existing snapshot from an earlier, higher-up
     * folder deletion (rare, but keeps the oldest/most-complete ancestry).
     */
    private void snapshotDeletedFolderPathIds(WarehouseFolder folder) {
        List<Document> affected = documentRepository.findByFolderId(folder.getId());
        if (affected.isEmpty()) {
            return;
        }
        UUID[] snapshot = appendToPath(folder.getPathIds(), folder.getId());
        for (Document document : affected) {
            if (document.getDeletedFolderPathIds() == null) {
                document.setDeletedFolderPathIds(snapshot);
            }
        }
        documentRepository.saveAll(affected);
    }

    public boolean folderExists(UUID id) {
        return repository.existsById(id);
    }

    // ------------------------------------------------------------- tree (Story 4)

    /** Loads all folders and assembles the parent -> children adjacency into a tree. */
    public List<FolderNode> getTree() {
        List<WarehouseFolder> all = repository.findAllByOrderByNameAsc();

        Map<UUID, List<WarehouseFolder>> childrenByParent = new HashMap<>();
        for (WarehouseFolder folder : all) {
            childrenByParent.computeIfAbsent(folder.getParentId(), k -> new ArrayList<>()).add(folder);
        }

        return buildNodes(null, childrenByParent);
    }

    private List<FolderNode> buildNodes(UUID parentId, Map<UUID, List<WarehouseFolder>> childrenByParent) {
        List<WarehouseFolder> children = childrenByParent.getOrDefault(parentId, List.of());
        List<FolderNode> nodes = new ArrayList<>(children.size());
        for (WarehouseFolder child : children) {
            nodes.add(new FolderNode(
                    child.getId(), child.getName(), child.getDepth(), buildNodes(child.getId(), childrenByParent)));
        }
        return nodes;
    }

    // ------------------------------------------------------------- helpers

    private static boolean containsId(UUID[] array, UUID id) {
        for (UUID candidate : array) {
            if (candidate.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static UUID[] appendToPath(UUID[] parentPath, UUID parentId) {
        UUID[] result = Arrays.copyOf(parentPath, parentPath.length + 1);
        result[parentPath.length] = parentId;
        return result;
    }

    private static ApiException maxDepthExceeded() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "MAX_DEPTH_EXCEEDED");
        body.put("message", "Vượt quá độ sâu tối đa 5 cấp thư mục");
        return new ApiException(HttpStatus.CONFLICT, body, "Max folder depth exceeded");
    }

    private static ApiException cycleDetected() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "CYCLE_DETECTED");
        body.put("message", "Không thể chuyển một thư mục vào chính nó hoặc vào thư mục con của nó");
        return new ApiException(HttpStatus.CONFLICT, body, "Cycle detected");
    }

    private static ApiException folderNotEmpty(String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "FOLDER_NOT_EMPTY");
        body.put(
                "message",
                "hasChildFolders".equals(reason)
                        ? "Thư mục còn thư mục con"
                        : "Thư mục còn tài liệu đang hoạt động");
        body.put("reason", reason);
        return new ApiException(HttpStatus.CONFLICT, body, "Folder not empty");
    }
}
