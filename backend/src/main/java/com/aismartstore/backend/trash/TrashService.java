package com.aismartstore.backend.trash;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.common.ForbiddenException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.common.SqlErrors;
import com.aismartstore.backend.displayname.DisplayNameValidator;
import com.aismartstore.backend.displayname.NameCheckRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentSpecifications;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.documents.PurgeState;
import com.aismartstore.backend.groups.DocumentGroupShare;
import com.aismartstore.backend.groups.DocumentGroupShareRepository;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.outbox.OutboxEvent;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import com.aismartstore.backend.warehouse.WarehouseService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xóa mềm / thùng rác (delete-restore-trash-01-xoa-mem-thung-rac.md). Owns
 * the actual read/write logic for every {@code /trash/*} route and the 2
 * mutation routes hanging off {@code /documents} ({@code
 * DocumentsController#trash}/{@code #trashPreview}) - those two live under
 * {@code /documents} purely for URL/route cohesion with the rest of the
 * {@code Document} entity's mutations (§1), the business logic itself stays
 * here.
 */
@Service
public class TrashService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TrashService.class);

    /** ingest.max_files_per_batch's default (§4.1 step 1) - also the hard ceiling on every trash/restore batch here. */
    private static final int MAX_BATCH_SIZE = 500;

    private final DocumentRepository documentRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final DocumentGroupShareRepository documentGroupShareRepository;
    private final TrashBatchRepository trashBatchRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;
    private final SystemConfigService systemConfigService;
    private final WarehouseService warehouseService;

    /**
     * Self-reference so {@link #restoreBatch} calling {@link #restore} per
     * item actually runs through the {@code @Transactional} proxy (same
     * idiom/rationale as {@code SystemConfigService#self} - a same-class
     * call would silently skip the transaction boundary).
     */
    private TrashService self;

    public TrashService(
            DocumentRepository documentRepository,
            GroupMemberRepository groupMemberRepository,
            DocumentGroupShareRepository documentGroupShareRepository,
            TrashBatchRepository trashBatchRepository,
            OutboxEventRepository outboxEventRepository,
            AuditService auditService,
            AuditRequestContext auditRequestContext,
            SystemConfigService systemConfigService,
            WarehouseService warehouseService) {
        this.documentRepository = documentRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.documentGroupShareRepository = documentGroupShareRepository;
        this.trashBatchRepository = trashBatchRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
        this.systemConfigService = systemConfigService;
        this.warehouseService = warehouseService;
        this.self = this;
    }

    @Autowired
    void setSelf(@Lazy TrashService self) {
        this.self = self;
    }

    // ---- API projections (§3) -------------------------------------------

    public record TrashResult(UUID batchId, int itemCount, OffsetDateTime purgeAfter) {
    }

    public record TrashItem(
            UUID documentId,
            String originalName,
            OffsetDateTime deletedAt,
            OffsetDateTime purgeAfter,
            long size,
            String deletedBySub,
            String deletedByUsername,
            String uploaderSub,
            String uploaderUsername,
            // Folder the document was deleted from, so the list can say where a restore lands.
            UUID folderId) {
    }

    public record TrashList(List<TrashItem> items, long total) {
    }

    /**
     * §3.1: {@code folderMissing}/{@code suggestedFolderId}/{@code
     * purgeState} are hard-coded {@code null}/{@code null}/{@code "active"}
     * for now (Task 9) - they depend on {@code deleted_folder_path_ids}/
     * {@code purge_state}, added by file 02's migration, not this one.
     */
    public record TrashDetail(
            UUID documentId,
            String originalName,
            long size,
            OffsetDateTime deletedAt,
            OffsetDateTime purgeAfter,
            String deletedBySub,
            String uploaderSub,
            UUID folderId,
            Boolean folderMissing,
            UUID suggestedFolderId,
            long activeGroupShareCount,
            String purgeState) {
    }

    public record RestoreResult(UUID documentId, UUID restoredFolderId, String restoredName, List<UUID> groupsLostEffect) {
    }

    public record RestoreBatchItem(
            UUID documentId, String status, String code, UUID restoredFolderId, List<UUID> groupsLostEffect) {

        static RestoreBatchItem restored(UUID documentId, UUID restoredFolderId, List<UUID> groupsLostEffect) {
            return new RestoreBatchItem(documentId, "RESTORED", null, restoredFolderId, groupsLostEffect);
        }

        static RestoreBatchItem failed(UUID documentId, HttpStatus status, String code) {
            String outcome = status == HttpStatus.CONFLICT ? "CONFLICT" : "ERROR";
            return new RestoreBatchItem(documentId, outcome, code, null, null);
        }
    }

    public record PreviewItem(UUID documentId, long activeGroupShareCount) {
    }

    public record ExpiryBucket(String bucket, long count, long bytes) {
    }

    public record UnrecoverableAccounts(long count, OffsetDateTime nearestPurgeAfter) {
    }

    public record TrashStats(long totalItems, long totalBytes, List<ExpiryBucket> byExpiryBucket,
            UnrecoverableAccounts unrecoverableAccounts) {
    }

    // ---- §4.1: xóa mềm ----------------------------------------------------

    @Transactional
    public TrashResult trash(KeycloakUser user, List<UUID> documentIds, String reason) {
        validateBatchSize(documentIds);

        List<Document> documents = documentRepository.findAllById(documentIds);
        Map<UUID, Document> byId = new HashMap<>();
        for (Document d : documents) {
            byId.put(d.getId(), d);
        }

        List<UUID> missingIds = documentIds.stream().filter(id -> !byId.containsKey(id)).distinct().toList();
        if (!missingIds.isEmpty()) {
            throw documentsNotFound(missingIds);
        }

        List<UUID> alreadyDeletedIds =
                documents.stream().filter(d -> d.getDeletedAt() != null).map(Document::getId).toList();
        if (!alreadyDeletedIds.isEmpty()) {
            throw alreadyDeleted(alreadyDeletedIds);
        }

        List<UUID> memberGroupIds = groupMemberRepository.findGroupIdsByUserSub(user.sub());
        Set<UUID> sharedDocumentIds = memberGroupIds.isEmpty()
                ? Set.of()
                : new HashSet<>(documentGroupShareRepository.findDocumentIdsSharedToAnyGroup(documentIds, memberGroupIds));

        List<UUID> forbiddenIds = documents.stream()
                .filter(d -> !user.sub().equals(d.getUploaderSub()) && !sharedDocumentIds.contains(d.getId()))
                .map(Document::getId)
                .toList();
        if (!forbiddenIds.isEmpty()) {
            // recordIndependently (REQUIRES_NEW), not record(): this whole method is
            // @Transactional and we're about to throw - an ordinary record() would join that
            // transaction and get rolled back by the throw right below, silently discarding the
            // denial entry (found while adding audit_log assertions for AC-6 - see AuditService
            // javadoc on recordIndependently for the full explanation). No document mutation has
            // happened in this branch, so there is nothing for the throw to need to roll back
            // together with the audit write.
            for (UUID id : forbiddenIds) {
                auditService.recordIndependently(new AuditService.AuditEntry(
                        user.preferredUsername(),
                        user.sub(),
                        "document.trash.denied",
                        "document",
                        id.toString(),
                        "failure",
                        auditRequestContext.method(),
                        auditRequestContext.path(),
                        403,
                        auditRequestContext.ip(),
                        Map.of("reason", "out_of_scope")));
            }
            throw forbiddenDocuments(forbiddenIds);
        }

        long retentionDays = systemConfigService.getLong(SystemConfigDefaults.RETENTION_TRASH_RETENTION_DAYS);
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime purgeAfter = now.plusDays(retentionDays);

        TrashBatch batch = new TrashBatch();
        batch.setActorSub(user.sub());
        batch.setActorUsername(user.preferredUsername());
        batch.setCreatedAt(now);
        batch.setItemCount(documents.size());
        trashBatchRepository.save(batch);

        for (Document document : documents) {
            List<UUID> groupIds = documentGroupShareRepository
                    .findByDocumentIdOrderBySharedAtAsc(document.getId())
                    .stream()
                    .map(DocumentGroupShare::getGroupId)
                    .toList();

            document.setDeletedAt(now);
            document.setDeletedBySub(user.sub());
            document.setDeletedByUsername(user.preferredUsername());
            document.setDeletedReason(reason);
            document.setPurgeAfter(purgeAfter);
            document.setTrashBatchId(batch.getId());
            // delete-restore-trash-02 §2.1: enters the hard-delete state
            // machine so TrashPurgeService's claim query (purge_state =
            // 'deleted') can find it once purgeAfter elapses.
            document.setPurgeState(PurgeState.DELETED);
            documentRepository.save(document);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("folderId", document.getFolderId() != null ? document.getFolderId().toString() : null);
            metadata.put("batchId", batch.getId().toString());
            metadata.put("groupIds", groupIds.stream().map(UUID::toString).toList());
            metadata.put("purgeAfter", purgeAfter.toString());
            auditService.record(new AuditService.AuditEntry(
                    user.preferredUsername(),
                    user.sub(),
                    "document.trash",
                    "document",
                    document.getId().toString(),
                    "success",
                    auditRequestContext.method(),
                    auditRequestContext.path(),
                    200,
                    auditRequestContext.ip(),
                    metadata));
        }

        writeTrashOutboxEvent(documentIds, now);

        return new TrashResult(batch.getId(), documents.size(), purgeAfter);
    }

    public List<PreviewItem> preview(List<UUID> documentIds) {
        validateBatchSize(documentIds);

        List<Object[]> rows = documentGroupShareRepository.countActiveSharesForDocuments(documentIds);
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return documentIds.stream().map(id -> new PreviewItem(id, counts.getOrDefault(id, 0L))).toList();
    }

    // ---- §4.2: xem thùng rác ------------------------------------------------

    public TrashList list(KeycloakUser user, String view, Integer limit, Integer offset) {
        int clampedLimit = Math.min(Math.max(limit == null ? 50 : limit, 1), 200);
        int clampedOffset = Math.max(offset == null ? 0 : offset, 0);
        String resolvedView = view == null || view.isBlank() ? "own" : view;

        Specification<Document> spec;
        if ("own".equals(resolvedView)) {
            spec = DocumentSpecifications.deletedByUser(user.sub());
        } else if ("uploaded".equals(resolvedView)) {
            spec = DocumentSpecifications.uploadedButDeletedByAnotherUser(user.sub());
        } else {
            throw invalidView(resolvedView);
        }

        List<Document> documents = documentRepository.searchTrash(spec, clampedLimit, clampedOffset);
        long total = documentRepository.count(spec);
        List<TrashItem> items = documents.stream().map(TrashService::toTrashItem).toList();
        return new TrashList(items, total);
    }

    public TrashDetail getOne(KeycloakUser user, UUID id) {
        Document document = documentRepository
                .findByIdAndDeletedAtIsNotNull(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy mục trong thùng rác"));
        requireRestorePermission(user, document);

        long activeGroupShareCount = documentGroupShareRepository.countActiveSharesForDocument(id);
        boolean folderMissing = document.getFolderId() == null;
        UUID suggestedFolderId = folderMissing ? suggestFolderId(document) : null;
        return new TrashDetail(
                document.getId(),
                document.getOriginalName(),
                document.getSize(),
                document.getDeletedAt(),
                document.getPurgeAfter(),
                document.getDeletedBySub(),
                document.getUploaderSub(),
                document.getFolderId(),
                folderMissing,
                suggestedFolderId,
                activeGroupShareCount,
                document.getPurgeState());
    }

    // ---- §4.3/file 02 §3.1: phục hồi, đủ 8 case xung đột --------------------

    /**
     * delete-restore-trash-02-xoa-cung-xung-dot.md §3 (thứ tự kiểm tra, dừng
     * ở điều kiện đầu tiên khớp): tồn tại & đã bị xóa mềm (404) -> quyền
     * (403) -> đang {@code purging} (409 {@code PURGING}) -> quá hạn/đã
     * purge (410 {@code GONE}) -> thư mục thiếu & không có {@code
     * targetFolderId} (409 {@code FOLDER_MISSING}) -> {@code targetFolderId}
     * không tồn tại (400) -> trùng tên & {@code onNameConflict} không phải
     * {@code AUTO_RENAME} (409 {@code NAME_CONFLICT}) -> thực hiện phục hồi
     * (200, kèm {@code groupsLostEffect} nếu có, §4.3 file 01). AC-3/AC-4/AC-5
     * (kho đầy, hạn mức cá nhân, tài khoản vô hiệu hóa) và AC-6 (nhóm chia sẻ
     * đã xóa) đều KHÔNG chặn ở đây - xem bảng §3.1 file 02 cho lý do từng
     * case.
     */
    @Transactional
    public RestoreResult restore(KeycloakUser user, UUID id, UUID targetFolderId, String onNameConflict) {
        Document document = documentRepository
                .findByIdAndDeletedAtIsNotNull(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy mục trong thùng rác"));
        requireRestorePermission(user, document);

        if (PurgeState.PURGING.equals(document.getPurgeState())) {
            throw purging();
        }
        boolean pastPurgeAfter =
                document.getPurgeAfter() != null && !document.getPurgeAfter().isAfter(OffsetDateTime.now());
        if (PurgeState.PURGED.equals(document.getPurgeState()) || pastPurgeAfter) {
            throw gone();
        }

        if (targetFolderId == null && document.getFolderId() == null) {
            throw folderMissing(suggestFolderId(document));
        }
        if (targetFolderId != null && !warehouseService.folderExists(targetFolderId)) {
            throw targetFolderNotFound();
        }
        UUID effectiveFolderId = targetFolderId != null ? targetFolderId : document.getFolderId();

        // SPEC-folder-upload-display-name BE-8: conflict is judged on display_name (case-insensitive,
        // live rows of every status, same predicate as UQ_document_display_name_alive).
        String oldName = document.getDisplayName();
        String restoredDisplayName = oldName;
        String restoredOriginalName = document.getOriginalName();
        if (displayNameTaken(effectiveFolderId, document, oldName)) {
            if (!"AUTO_RENAME".equals(onNameConflict)) {
                throw nameConflict(DisplayNameValidator.generateSuffixName(document.getOriginalName()));
            }
            // "Đổi tên": suffix name (Java generator), up to 3 candidates; both names get the new value.
            String candidate = null;
            for (int attempt = 0; attempt < 3 && candidate == null; attempt++) {
                String next = DisplayNameValidator.generateSuffixName(document.getOriginalName());
                if (!displayNameTaken(effectiveFolderId, document, next)) {
                    candidate = next;
                }
            }
            if (candidate == null) {
                throw nameConflict(DisplayNameValidator.generateSuffixName(document.getOriginalName()));
            }
            restoredDisplayName = candidate;
            restoredOriginalName = candidate;
        }

        document.setOriginalName(restoredOriginalName);
        document.setDisplayName(restoredDisplayName);
        document.setFolderId(effectiveFolderId);
        document.setDeletedAt(null);
        document.setDeletedBySub(null);
        document.setDeletedByUsername(null);
        document.setDeletedReason(null);
        document.setPurgeAfter(null);
        document.setPurgeState(PurgeState.ACTIVE);
        // trashBatchId intentionally kept (history, §4.3 step 4).
        try {
            documentRepository.saveAndFlush(document);
        } catch (DataIntegrityViolationException e) {
            if (SqlErrors.isUniqueViolation(e)) {
                // lost a race with a concurrent upload/restore into the same name
                throw nameConflict(DisplayNameValidator.generateSuffixName(document.getOriginalName()));
            }
            throw e;
        }
        log.info("restore display_name renamed={} context=restore", !restoredDisplayName.equals(oldName));

        List<UUID> groupsLostEffect = documentGroupShareRepository.findDeletedGroupIdsForDocument(id);

        writeRestoredOutboxEvent(id);

        auditService.record(new AuditService.AuditEntry(
                user.preferredUsername(),
                user.sub(),
                "document.restore",
                "document",
                id.toString(),
                "success",
                auditRequestContext.method(),
                auditRequestContext.path(),
                200,
                auditRequestContext.ip(),
                Map.of(
                        "restoredFolderId", document.getFolderId() != null ? document.getFolderId().toString() : "",
                        "oldDisplayName", oldName,
                        "newDisplayName", restoredDisplayName,
                        "groupsLostEffect", groupsLostEffect.stream().map(UUID::toString).toList())));

        return new RestoreResult(document.getId(), document.getFolderId(), document.getDisplayName(), groupsLostEffect);
    }

    public List<RestoreBatchItem> restoreBatch(
            KeycloakUser user, List<UUID> documentIds, UUID targetFolderId, String onNameConflict) {
        validateBatchSize(documentIds);

        List<RestoreBatchItem> results = new ArrayList<>(documentIds.size());
        for (UUID id : documentIds) {
            try {
                RestoreResult result = self.restore(user, id, targetFolderId, onNameConflict);
                results.add(RestoreBatchItem.restored(id, result.restoredFolderId(), result.groupsLostEffect()));
            } catch (ApiException e) {
                results.add(RestoreBatchItem.failed(id, e.getStatus(), codeOf(e)));
            }
        }
        return results;
    }

    // ---- §4.5: R3 stats -----------------------------------------------------

    public TrashStats stats() {
        long totalItems = documentRepository.count(hasAnyDeletedAt());
        long totalBytes = documentRepository.sumSizeByStatusAndDeletedAtIsNotNull(DocumentStatus.CLEAN);

        List<ExpiryBucket> buckets = new ArrayList<>();
        for (Object[] row : documentRepository.countByExpiryBucket()) {
            buckets.add(new ExpiryBucket((String) row[0], ((Number) row[1]).longValue(), ((Number) row[2]).longValue()));
        }

        UnrecoverableAccounts unrecoverable = new UnrecoverableAccounts(0, null);
        List<Object[]> unrecoverableRows = documentRepository.countUnrecoverableInTrash();
        if (!unrecoverableRows.isEmpty()) {
            Object[] row = unrecoverableRows.get(0);
            long count = row[0] == null ? 0 : ((Number) row[0]).longValue();
            OffsetDateTime nearestPurgeAfter = toOffsetDateTime(row[1]);
            unrecoverable = new UnrecoverableAccounts(count, nearestPurgeAfter);
        }

        return new TrashStats(totalItems, totalBytes, buckets, unrecoverable);
    }

    // ---- internals ------------------------------------------------------

    /**
     * The JDBC driver/Hibernate maps a native {@code min(timestamptz)}
     * aggregate to {@link java.time.Instant} (not {@link OffsetDateTime},
     * unlike a plain entity column mapping) - normalize here instead of
     * assuming either type.
     */
    private static OffsetDateTime toOffsetDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        if (value instanceof java.time.Instant instant) {
            return instant.atOffset(java.time.ZoneOffset.UTC);
        }
        throw new IllegalStateException("Unexpected temporal type for purge_after: " + value.getClass());
    }

    private static Specification<Document> hasAnyDeletedAt() {
        return (root, query, cb) -> cb.isNotNull(root.get("deletedAt"));
    }

    private void requireRestorePermission(KeycloakUser user, Document document) {
        boolean isDeleter = user.sub().equals(document.getDeletedBySub());
        boolean isUploader = user.sub().equals(document.getUploaderSub());
        if (!isDeleter && !isUploader) {
            throw new ForbiddenException("Bạn không có quyền xem/phục hồi mục này trong thùng rác");
        }
    }

    private void validateBatchSize(List<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty() || documentIds.size() > MAX_BATCH_SIZE) {
            throw invalidBatchSize();
        }
    }

    /**
     * File 02 §4.4: walks {@code deletedFolderPathIds} leaf-to-root, returns
     * the first ancestor id that still exists - {@code null} if none do (or
     * if there is no snapshot at all, e.g. historical data predating V30).
     */
    private UUID suggestFolderId(Document document) {
        UUID[] pathIds = document.getDeletedFolderPathIds();
        if (pathIds == null) {
            return null;
        }
        for (int i = pathIds.length - 1; i >= 0; i--) {
            if (warehouseService.folderExists(pathIds[i])) {
                return pathIds[i];
            }
        }
        return null;
    }

    private boolean displayNameTaken(UUID folderId, Document document, String name) {
        return documentRepository.existsAliveDisplayName(
                (folderId == null ? NameCheckRepository.SENTINEL : folderId).toString(),
                document.getRelativePath(), name, document.getId().toString());
    }

    private void writeTrashOutboxEvent(List<UUID> documentIds, OffsetDateTime changedAt) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType("document.trash");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentIds", documentIds.stream().map(UUID::toString).toList());
        payload.put("changedAt", changedAt.toString());
        event.setPayload(payload);
        event.setCreatedAt(changedAt);
        outboxEventRepository.save(event);
    }

    private void writeRestoredOutboxEvent(UUID documentId) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType("document.restored");
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", documentId.toString());
        payload.put("changedAt", now.toString());
        event.setPayload(payload);
        event.setCreatedAt(now);
        outboxEventRepository.save(event);
    }

    private static TrashItem toTrashItem(Document d) {
        return new TrashItem(
                d.getId(),
                d.getOriginalName(),
                d.getDeletedAt(),
                d.getPurgeAfter(),
                d.getSize(),
                d.getDeletedBySub(),
                d.getDeletedByUsername(),
                d.getUploaderSub(),
                d.getUploaderUsername(),
                d.getFolderId());
    }

    @SuppressWarnings("unchecked")
    private static String codeOf(ApiException ex) {
        if (ex.getBody() instanceof Map<?, ?> body) {
            Object code = ((Map<String, Object>) body).get("code");
            return code != null ? code.toString() : null;
        }
        return null;
    }

    // ---- error bodies -----------------------------------------------------

    private static ApiException invalidBatchSize() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "INVALID_BATCH_SIZE");
        body.put("message", "documentIds phải có từ 1 đến " + MAX_BATCH_SIZE + " phần tử");
        return new ApiException(HttpStatus.BAD_REQUEST, body, "Invalid batch size");
    }

    private static ApiException documentsNotFound(List<UUID> ids) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "DOCUMENT_NOT_FOUND");
        body.put("message", "Một hoặc nhiều tài liệu không tồn tại");
        body.put("documentIds", ids.stream().map(UUID::toString).toList());
        return new ApiException(HttpStatus.NOT_FOUND, body, "Document(s) not found");
    }

    private static ApiException alreadyDeleted(List<UUID> ids) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "DOCUMENT_ALREADY_DELETED");
        body.put("message", "Một hoặc nhiều tài liệu đã ở trong thùng rác");
        body.put("documentIds", ids.stream().map(UUID::toString).toList());
        return new ApiException(HttpStatus.CONFLICT, body, "Document(s) already in trash");
    }

    private static ApiException forbiddenDocuments(List<UUID> ids) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "FORBIDDEN");
        body.put("message", "Một hoặc nhiều tài liệu không thuộc phạm vi cho phép xóa");
        body.put("documentIds", ids.stream().map(UUID::toString).toList());
        return new ApiException(HttpStatus.FORBIDDEN, body, "Document(s) out of scope");
    }

    // ---- error bodies (file 02 §3.1: 8-case conflict table) ---------------

    private static ApiException purging() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "PURGING");
        body.put("message", "Tài liệu đang được xóa cứng, chưa thể phục hồi");
        return new ApiException(HttpStatus.CONFLICT, body, "Document is being purged");
    }

    private static ApiException gone() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "GONE");
        body.put(
                "message",
                "Tài liệu đã quá hạn lưu hoặc đã bị xóa cứng, không thể phục hồi qua thùng rác. "
                        + "Liên hệ quản trị để được hỗ trợ khôi phục từ bản sao lưu (nếu có)");
        return new ApiException(HttpStatus.GONE, body, "Document already purged or past retention");
    }

    private static ApiException folderMissing(UUID suggestedFolderId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "FOLDER_MISSING");
        body.put("message", "Thư mục gốc của tài liệu đã bị xóa, vui lòng chọn thư mục đích để phục hồi");
        body.put("suggestedFolderId", suggestedFolderId != null ? suggestedFolderId.toString() : null);
        return new ApiException(HttpStatus.CONFLICT, body, "Original folder no longer exists");
    }

    private static ApiException targetFolderNotFound() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "TARGET_FOLDER_NOT_FOUND");
        body.put("message", "Thư mục đích không tồn tại");
        return new ApiException(HttpStatus.BAD_REQUEST, body, "targetFolderId not found");
    }

    private static ApiException nameConflict(String suggestedName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "NAME_CONFLICT");
        body.put("message", "Đã tồn tại tài liệu cùng tên tại thư mục đích");
        body.put("suggestedName", suggestedName);
        return new ApiException(HttpStatus.CONFLICT, body, "Name conflict at destination folder");
    }

    private static ApiException invalidView(String view) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "INVALID_VIEW");
        body.put("message", "view phải là 'own' hoặc 'uploaded', nhận được: " + view);
        return new ApiException(HttpStatus.BAD_REQUEST, body, "Invalid view");
    }
}
