package com.aismartstore.backend.trash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.common.ForbiddenException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.documents.PurgeState;
import com.aismartstore.backend.groups.DocumentGroupShareRepository;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import com.aismartstore.backend.warehouse.WarehouseService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit coverage for delete-restore-trash-01-xoa-mem-thung-rac.md §5
 * ("Unit - TrashService.trash", "Unit - quyền phục hồi") - every collaborator
 * mocked, no persistence context (see {@code TrashControllerIT} for the
 * real-DB end-to-end coverage: outbox/audit rows, BusinessDataScope
 * interplay, group-share preservation).
 */
class TrashServiceTest {

    private DocumentRepository documentRepository;
    private GroupMemberRepository groupMemberRepository;
    private DocumentGroupShareRepository documentGroupShareRepository;
    private TrashBatchRepository trashBatchRepository;
    private OutboxEventRepository outboxEventRepository;
    private AuditService auditService;
    private AuditRequestContext auditRequestContext;
    private SystemConfigService systemConfigService;
    private WarehouseService warehouseService;
    private TrashService service;

    private final KeycloakUser owner = new KeycloakUser("owner-sub", "owner", List.of());
    private final KeycloakUser stranger = new KeycloakUser("stranger-sub", "stranger", List.of());

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        groupMemberRepository = mock(GroupMemberRepository.class);
        documentGroupShareRepository = mock(DocumentGroupShareRepository.class);
        trashBatchRepository = mock(TrashBatchRepository.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        auditService = mock(AuditService.class);
        auditRequestContext = mock(AuditRequestContext.class);
        systemConfigService = mock(SystemConfigService.class);
        warehouseService = mock(WarehouseService.class);

        service = new TrashService(
                documentRepository, groupMemberRepository, documentGroupShareRepository, trashBatchRepository,
                outboxEventRepository, auditService, auditRequestContext, systemConfigService, warehouseService);

        when(groupMemberRepository.findGroupIdsByUserSub(any())).thenReturn(List.of());
        when(documentGroupShareRepository.findDocumentIdsSharedToAnyGroup(anyList(), anyList())).thenReturn(List.of());
        when(documentGroupShareRepository.findByDocumentIdOrderBySharedAtAsc(any())).thenReturn(List.of());
        when(systemConfigService.getLong(SystemConfigDefaults.RETENTION_TRASH_RETENTION_DAYS)).thenReturn(365L);
        when(trashBatchRepository.save(any())).thenAnswer(invocation -> {
            TrashBatch batch = invocation.getArgument(0);
            if (batch.getId() == null) {
                ReflectionTestUtils.setField(batch, "id", UUID.randomUUID());
            }
            return batch;
        });
        // File 02 §3.1: most tests don't care about the FOLDER_MISSING/
        // TARGET_FOLDER_NOT_FOUND cases - default every folder to "exists"
        // so they aren't accidentally exercised; the dedicated tests below
        // override this per case.
        when(warehouseService.folderExists(any())).thenReturn(true);
        when(documentRepository.findFirstByFolderIdAndRelativePathAndOriginalNameAndStatusAndDeletedAtIsNull(
                        any(), any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    private static Document activeDocument(String uploaderSub) {
        Document document = new Document();
        ReflectionTestUtils.setField(document, "id", UUID.randomUUID());
        document.setOriginalName("f.txt");
        document.setUploaderSub(uploaderSub);
        document.setUploaderUsername(uploaderSub);
        document.setSize(10);
        document.setStatus(DocumentStatus.CLEAN);
        // File 02 §3.1 AC-1: non-null by default so restore()'s FOLDER_MISSING
        // check isn't accidentally triggered by tests that don't care about it.
        document.setFolderId(UUID.randomUUID());
        return document;
    }

    // ---- §3.2/§4.1: xóa mềm - "tất cả hoặc không" ----------------------------

    @Test
    void trashAllValidOwnedDocumentsSucceedsAndWritesOneBatchAndOneOutboxEvent() {
        Document d1 = activeDocument(owner.sub());
        Document d2 = activeDocument(owner.sub());
        when(documentRepository.findAllById(List.of(d1.getId(), d2.getId()))).thenReturn(List.of(d1, d2));

        TrashService.TrashResult result = service.trash(owner, List.of(d1.getId(), d2.getId()), "no longer needed");

        assertThat(result.itemCount()).isEqualTo(2);
        assertThat(d1.getDeletedAt()).isNotNull();
        assertThat(d1.getDeletedBySub()).isEqualTo(owner.sub());
        assertThat(d1.getPurgeAfter()).isEqualTo(d1.getDeletedAt().plusDays(365));
        assertThat(d2.getDeletedAt()).isNotNull();
        verify(documentRepository, times(2)).save(any());
        verify(trashBatchRepository, times(1)).save(any());
        verify(outboxEventRepository, times(1)).save(any());
        verify(auditService, times(2)).record(any());
    }

    @Test
    void trashRejectsWholeBatchWhenOneDocumentIsOutOfScopeAndRollsBackNothing() {
        Document owned = activeDocument(owner.sub());
        Document notOwned = activeDocument(stranger.sub());
        when(documentRepository.findAllById(List.of(owned.getId(), notOwned.getId())))
                .thenReturn(List.of(owned, notOwned));

        assertThatThrownBy(() -> service.trash(owner, List.of(owned.getId(), notOwned.getId()), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(403));

        assertThat(owned.getDeletedAt()).isNull();
        assertThat(notOwned.getDeletedAt()).isNull();
        verify(documentRepository, never()).save(any());
        verify(trashBatchRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void trashRejectsWhenAnyDocumentIsMissing() {
        UUID missingId = UUID.randomUUID();
        when(documentRepository.findAllById(List.of(missingId))).thenReturn(List.of());

        assertThatThrownBy(() -> service.trash(owner, List.of(missingId), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    void trashRejectsWhenAnyDocumentIsAlreadyInTrash() {
        Document alreadyDeleted = activeDocument(owner.sub());
        alreadyDeleted.setDeletedAt(OffsetDateTime.now());
        when(documentRepository.findAllById(List.of(alreadyDeleted.getId()))).thenReturn(List.of(alreadyDeleted));

        assertThatThrownBy(() -> service.trash(owner, List.of(alreadyDeleted.getId()), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(409));

        verify(documentRepository, never()).save(any());
    }

    @Test
    void trashRejectsBatchSizeOutOfBounds() {
        assertThatThrownBy(() -> service.trash(owner, List.of(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(400));

        List<UUID> tooMany = java.util.stream.Stream.generate(UUID::randomUUID).limit(501).toList();
        assertThatThrownBy(() -> service.trash(owner, tooMany, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(400));
    }

    // ---- §3.1: preview (activeGroupShareCount cho POST /documents/trash-preview) ----

    @Test
    void previewReturnsActiveGroupShareCountPerDocumentDefaultingToZeroWhenAbsent() {
        UUID d1 = UUID.randomUUID(); // N=3 active group shares
        UUID d2 = UUID.randomUUID(); // N=0 (no row returned by the batch query)
        UUID d3 = UUID.randomUUID(); // N=1, exercised alongside d1/d2 in the same call
        when(documentGroupShareRepository.countActiveSharesForDocuments(List.of(d1, d2, d3)))
                .thenReturn(List.of(new Object[] {d1, 3L}, new Object[] {d3, 1L}));

        List<TrashService.PreviewItem> result = service.preview(List.of(d1, d2, d3));

        assertThat(result).containsExactly(
                new TrashService.PreviewItem(d1, 3L),
                new TrashService.PreviewItem(d2, 0L),
                new TrashService.PreviewItem(d3, 1L));
    }

    // ---- §4.3/Quyết định 6 SA: quyền phục hồi ---------------------------------

    @Test
    void restoreForbiddenWhenCallerIsNeitherDeleterNorUploader() {
        Document trashed = activeDocument("uploader-sub");
        trashed.setDeletedAt(OffsetDateTime.now());
        trashed.setDeletedBySub("deleter-sub");
        when(documentRepository.findByIdAndDeletedAtIsNotNull(trashed.getId())).thenReturn(Optional.of(trashed));

        assertThatThrownBy(() -> service.restore(stranger, trashed.getId(), null, null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void restoreAllowedWhenCallerIsTheUploaderEvenIfSomeoneElseDeletedIt() {
        Document trashed = activeDocument(owner.sub());
        trashed.setDeletedAt(OffsetDateTime.now());
        trashed.setDeletedBySub("some-other-user");
        when(documentRepository.findByIdAndDeletedAtIsNotNull(trashed.getId())).thenReturn(Optional.of(trashed));
        when(documentGroupShareRepository.findDeletedGroupIdsForDocument(trashed.getId())).thenReturn(List.of());

        TrashService.RestoreResult result = service.restore(owner, trashed.getId(), null, null);

        assertThat(result.documentId()).isEqualTo(trashed.getId());
        assertThat(trashed.getDeletedAt()).isNull();
        assertThat(trashed.getDeletedBySub()).isNull();
    }

    @Test
    void restoreAllowedWhenCallerIsTheDeleterEvenIfSomeoneElseUploadedIt() {
        Document trashed = activeDocument("original-uploader");
        trashed.setDeletedAt(OffsetDateTime.now());
        trashed.setDeletedBySub(owner.sub());
        when(documentRepository.findByIdAndDeletedAtIsNotNull(trashed.getId())).thenReturn(Optional.of(trashed));
        when(documentGroupShareRepository.findDeletedGroupIdsForDocument(trashed.getId())).thenReturn(List.of());

        service.restore(owner, trashed.getId(), null, null); // must not throw

        assertThat(trashed.getDeletedAt()).isNull();
    }

    @Test
    void restoreOverridesFolderIdWhenTargetFolderIdProvided() {
        Document trashed = activeDocument(owner.sub());
        trashed.setDeletedAt(OffsetDateTime.now());
        trashed.setDeletedBySub(owner.sub());
        UUID newFolderId = UUID.randomUUID();
        when(documentRepository.findByIdAndDeletedAtIsNotNull(trashed.getId())).thenReturn(Optional.of(trashed));
        when(documentGroupShareRepository.findDeletedGroupIdsForDocument(trashed.getId())).thenReturn(List.of());

        TrashService.RestoreResult result = service.restore(owner, trashed.getId(), newFolderId, null);

        assertThat(result.restoredFolderId()).isEqualTo(newFolderId);
        assertThat(trashed.getFolderId()).isEqualTo(newFolderId);
    }

    @Test
    void restoreThrowsNotFoundWhenItemIsNotInTrash() {
        UUID id = UUID.randomUUID();
        when(documentRepository.findByIdAndDeletedAtIsNotNull(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.restore(owner, id, null, null)).isInstanceOf(NotFoundException.class);
    }

    // ---- file 02 §3.1: 8-case conflict table (thứ tự kiểm tra) ---------------

    private static Document trashedDocument(String uploaderSub) {
        Document document = activeDocument(uploaderSub);
        document.setDeletedAt(OffsetDateTime.now());
        document.setDeletedBySub(uploaderSub);
        document.setPurgeState(PurgeState.DELETED);
        document.setPurgeAfter(OffsetDateTime.now().plusDays(300));
        return document;
    }

    @Test
    void restoreRejectsWithPurgingWhenDocumentIsBeingPurged() {
        Document trashed = trashedDocument(owner.sub());
        trashed.setPurgeState(PurgeState.PURGING);
        when(documentRepository.findByIdAndDeletedAtIsNotNull(trashed.getId())).thenReturn(Optional.of(trashed));

        assertThatThrownBy(() -> service.restore(owner, trashed.getId(), null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                    assertThat(codeOfBody(ex)).isEqualTo("PURGING");
                });
    }

    @Test
    void restoreRejectsWithGoneWhenAlreadyPurged() {
        Document trashed = trashedDocument(owner.sub());
        trashed.setPurgeState(PurgeState.PURGED);
        when(documentRepository.findByIdAndDeletedAtIsNotNull(trashed.getId())).thenReturn(Optional.of(trashed));

        assertThatThrownBy(() -> service.restore(owner, trashed.getId(), null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getStatus().value()).isEqualTo(410);
                    assertThat(codeOfBody(ex)).isEqualTo("GONE");
                });
    }

    @Test
    void restoreRejectsWithGoneWhenPastPurgeAfter() {
        Document trashed = trashedDocument(owner.sub());
        trashed.setPurgeAfter(OffsetDateTime.now().minusMinutes(1));
        when(documentRepository.findByIdAndDeletedAtIsNotNull(trashed.getId())).thenReturn(Optional.of(trashed));

        assertThatThrownBy(() -> service.restore(owner, trashed.getId(), null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(410));
    }

    @Test
    void restoreRejectsWithFolderMissingWhenOriginalFolderGoneAndNoTargetGiven() {
        Document trashed = trashedDocument(owner.sub());
        trashed.setFolderId(null);
        when(documentRepository.findByIdAndDeletedAtIsNotNull(trashed.getId())).thenReturn(Optional.of(trashed));

        assertThatThrownBy(() -> service.restore(owner, trashed.getId(), null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                    assertThat(codeOfBody(ex)).isEqualTo("FOLDER_MISSING");
                });
    }

    @Test
    void restoreSuggestsNearestSurvivingAncestorWhenFolderMissing() {
        Document trashed = trashedDocument(owner.sub());
        trashed.setFolderId(null);
        UUID grandparent = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        trashed.setDeletedFolderPathIds(new UUID[] {grandparent, parent});
        when(documentRepository.findByIdAndDeletedAtIsNotNull(trashed.getId())).thenReturn(Optional.of(trashed));
        when(warehouseService.folderExists(parent)).thenReturn(false); // leaf - also gone
        when(warehouseService.folderExists(grandparent)).thenReturn(true); // still exists

        assertThatThrownBy(() -> service.restore(owner, trashed.getId(), null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(((Map<?, ?>) ex.getBody()).get("suggestedFolderId")).isEqualTo(grandparent.toString());
                });
    }

    @Test
    void restoreRejectsWithBadRequestWhenTargetFolderIdDoesNotExist() {
        Document trashed = trashedDocument(owner.sub());
        UUID missingFolder = UUID.randomUUID();
        when(documentRepository.findByIdAndDeletedAtIsNotNull(trashed.getId())).thenReturn(Optional.of(trashed));
        when(warehouseService.folderExists(missingFolder)).thenReturn(false);

        assertThatThrownBy(() -> service.restore(owner, trashed.getId(), missingFolder, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(400));
    }

    @Test
    void restoreRejectsWithNameConflictWhenNotAutoRenamed() {
        Document trashed = trashedDocument(owner.sub());
        Document existing = activeDocument("someone");
        when(documentRepository.findByIdAndDeletedAtIsNotNull(trashed.getId())).thenReturn(Optional.of(trashed));
        when(documentRepository.findFirstByFolderIdAndRelativePathAndOriginalNameAndStatusAndDeletedAtIsNull(
                        trashed.getFolderId(), trashed.getRelativePath(), trashed.getOriginalName(),
                        DocumentStatus.CLEAN))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.restore(owner, trashed.getId(), null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                    assertThat(codeOfBody(ex)).isEqualTo("NAME_CONFLICT");
                    assertThat(((Map<?, ?>) ex.getBody()).get("suggestedName")).isNotNull();
                });
    }

    @Test
    void restoreAutoRenamesWhenNameConflictAndAutoRenameRequested() {
        Document trashed = trashedDocument(owner.sub());
        Document existing = activeDocument("someone");
        when(documentRepository.findByIdAndDeletedAtIsNotNull(trashed.getId())).thenReturn(Optional.of(trashed));
        when(documentRepository.findFirstByFolderIdAndRelativePathAndOriginalNameAndStatusAndDeletedAtIsNull(
                        trashed.getFolderId(), trashed.getRelativePath(), trashed.getOriginalName(),
                        DocumentStatus.CLEAN))
                .thenReturn(Optional.of(existing));
        when(documentGroupShareRepository.findDeletedGroupIdsForDocument(trashed.getId())).thenReturn(List.of());

        TrashService.RestoreResult result = service.restore(owner, trashed.getId(), null, "AUTO_RENAME");

        assertThat(result.restoredName()).isNotEqualTo("f.txt");
        assertThat(result.restoredName()).startsWith("f.txt (khôi phục ");
        assertThat(trashed.getPurgeState()).isEqualTo(PurgeState.ACTIVE);
    }

    @SuppressWarnings("unchecked")
    private static String codeOfBody(ApiException ex) {
        return ex.getBody() instanceof Map<?, ?> body ? String.valueOf(((Map<String, Object>) body).get("code")) : null;
    }

    // ---- restoreBatch: mỗi item độc lập ---------------------------------------

    @Test
    void restoreBatchReportsErrorForForbiddenItemsButRestoresTheRest() {
        Document restorable = activeDocument(owner.sub());
        restorable.setDeletedAt(OffsetDateTime.now());
        restorable.setDeletedBySub(owner.sub());
        Document forbidden = activeDocument("someone-else");
        forbidden.setDeletedAt(OffsetDateTime.now());
        forbidden.setDeletedBySub("someone-else");

        when(documentRepository.findByIdAndDeletedAtIsNotNull(restorable.getId())).thenReturn(Optional.of(restorable));
        when(documentRepository.findByIdAndDeletedAtIsNotNull(forbidden.getId())).thenReturn(Optional.of(forbidden));
        when(documentGroupShareRepository.findDeletedGroupIdsForDocument(any())).thenReturn(List.of());

        List<TrashService.RestoreBatchItem> results =
                service.restoreBatch(owner, List.of(restorable.getId(), forbidden.getId()), null, null);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).status()).isEqualTo("RESTORED");
        assertThat(results.get(1).status()).isEqualTo("ERROR");
        assertThat(restorable.getDeletedAt()).isNull();
        assertThat(forbidden.getDeletedAt()).isNotNull(); // untouched by the failed item
    }
}
