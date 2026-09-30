package com.aismartstore.backend.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.common.ConflictException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.warehouse.dto.CreateFolderDto;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit coverage for warehouse-structure.md §5 ("WarehouseFolderServiceTest"). */
class WarehouseFolderServiceTest {

    private WarehouseFolderRepository repository;
    private DocumentRepository documentRepository;
    private WarehouseService service;

    private final KeycloakUser admin = new KeycloakUser("admin-sub", "admin", List.of("quan-tri"));

    @BeforeEach
    void setUp() {
        repository = mock(WarehouseFolderRepository.class);
        documentRepository = mock(DocumentRepository.class);
        service = new WarehouseService(repository, documentRepository);
    }

    private static WarehouseFolder folder(UUID id, UUID parentId, UUID[] pathIds, int depth) {
        WarehouseFolder f = new WarehouseFolder();
        ReflectionTestUtils.setField(f, "id", id);
        f.setParentId(parentId);
        f.setPathIds(pathIds);
        f.setDepth((short) depth);
        f.setName("folder");
        return f;
    }

    private static UUID[] randomPath(int length) {
        UUID[] path = new UUID[length];
        for (int i = 0; i < length; i++) {
            path[i] = UUID.randomUUID();
        }
        return path;
    }

    private static UUID[] append(UUID[] path, UUID id) {
        UUID[] result = new UUID[path.length + 1];
        System.arraycopy(path, 0, result, 0, path.length);
        result[path.length] = id;
        return result;
    }

    @SuppressWarnings("unchecked")
    private static String codeOf(Throwable ex) {
        return (String) ((Map<String, Object>) ((ApiException) ex).getBody()).get("code");
    }

    @SuppressWarnings("unchecked")
    private static String reasonOf(Throwable ex) {
        return (String) ((Map<String, Object>) ((ApiException) ex).getBody()).get("reason");
    }

    // ---- Story 1 AC-3: name format (Bean Validation on the DTO) ------------

    @Test
    void nameValidationAcceptsSpacesDigitsHyphenUnderscoreUpTo100Chars() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        assertThat(validator.validate(new CreateFolderDto("Ho so vu an", null))).isEmpty();
        assertThat(validator.validate(new CreateFolderDto("A-1_b", null))).isEmpty();
        assertThat(validator.validate(new CreateFolderDto("a".repeat(100), null))).isEmpty();
    }

    @Test
    void nameValidationRejectsAccentsSpecialCharsOverlengthAndBlank() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        assertThat(validator.validate(new CreateFolderDto("Hồ sơ", null))).isNotEmpty();
        assertThat(validator.validate(new CreateFolderDto("bad@#/name", null))).isNotEmpty();
        assertThat(validator.validate(new CreateFolderDto("a".repeat(101), null))).isNotEmpty();
        assertThat(validator.validate(new CreateFolderDto("", null))).isNotEmpty();
    }

    // ---- Story 1 AC-4: duplicate name at the same level ---------------------

    @Test
    void createDuplicateNameSameParentThrowsConflict() {
        when(repository.save(any()))
                .thenThrow(new DataIntegrityViolationException("dup", new SQLException("dup", "23505")));

        assertThatThrownBy(() -> service.createFolder(admin, "Ho so", null)).isInstanceOf(ConflictException.class);
    }

    @Test
    void renameDuplicateNameSameParentThrowsConflict() {
        WarehouseFolder existing = folder(UUID.randomUUID(), null, new UUID[0], 1);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any()))
                .thenThrow(new DataIntegrityViolationException("dup", new SQLException("dup", "23505")));

        assertThatThrownBy(() -> service.renameFolder(admin, existing.getId(), "Dup"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void renameDoesNotTouchPathIdsOrDepth() {
        UUID id = UUID.randomUUID();
        UUID[] path = randomPath(2);
        WarehouseFolder existing = folder(id, UUID.randomUUID(), path, 3);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WarehouseFolder renamed = service.renameFolder(admin, id, "New name");

        assertThat(renamed.getName()).isEqualTo("New name");
        assertThat(renamed.getPathIds()).isEqualTo(path);
        assertThat(renamed.getDepth()).isEqualTo((short) 3);
    }

    // ---- Story 1 AC-2 / Story 2 AC-4: max depth 5 -----------------------------

    @Test
    void createAtDepthFiveSucceeds() {
        WarehouseFolder parent = folder(UUID.randomUUID(), null, randomPath(3), 4);
        when(repository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WarehouseFolder created = service.createFolder(admin, "Child", parent.getId());

        assertThat(created.getDepth()).isEqualTo((short) 5);
    }

    @Test
    void createChildOfDepthFiveFolderThrowsMaxDepthExceeded() {
        WarehouseFolder parent = folder(UUID.randomUUID(), null, randomPath(4), 5);
        when(repository.findById(parent.getId())).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> service.createFolder(admin, "Child", parent.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("MAX_DEPTH_EXCEEDED"));
        verify(repository, never()).save(any());
    }

    // ---- Story 2 AC-2: cycle detection ---------------------------------------

    @Test
    void moveIntoSelfThrowsCycleDetected() {
        UUID id = UUID.randomUUID();
        WarehouseFolder a = folder(id, null, new UUID[0], 1);
        when(repository.findById(id)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.moveFolder(admin, id, id))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("CYCLE_DETECTED"));
    }

    @Test
    void moveIntoOwnDescendantThrowsCycleDetected() {
        UUID aId = UUID.randomUUID();
        WarehouseFolder a = folder(aId, null, new UUID[0], 1);
        WarehouseFolder b = folder(UUID.randomUUID(), aId, new UUID[] {aId}, 2);
        when(repository.findById(aId)).thenReturn(Optional.of(a));
        when(repository.findById(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.moveFolder(admin, aId, b.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("CYCLE_DETECTED"));
    }

    // ---- Story 2 AC-4: subtree-aware max depth check -------------------------

    @Test
    void moveExceedingMaxDepthConsideringWholeSubtreeThrowsMaxDepthExceeded() {
        UUID folderId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID newParentId = UUID.randomUUID();

        // folder is depth 2; it has a grandchild at depth 4 (folder -> child -> grandchild).
        WarehouseFolder folder = folder(folderId, UUID.randomUUID(), randomPath(1), 2);
        WarehouseFolder child = folder(childId, folderId, append(folder.getPathIds(), folderId), 3);
        WarehouseFolder grandchild =
                folder(UUID.randomUUID(), childId, append(child.getPathIds(), childId), 4);
        // newParent is depth 3 -> folder's own new depth would be 4 (still <= 5 on its own),
        // but delta=+2 pushes the depth-4 grandchild to depth 6.
        WarehouseFolder newParent = folder(newParentId, UUID.randomUUID(), randomPath(2), 3);

        when(repository.findById(folderId)).thenReturn(Optional.of(folder));
        when(repository.findById(newParentId)).thenReturn(Optional.of(newParent));
        when(repository.findDescendants(folderId)).thenReturn(List.of(child, grandchild));

        assertThatThrownBy(() -> service.moveFolder(admin, folderId, newParentId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo("MAX_DEPTH_EXCEEDED"));
        verify(repository, never()).save(any());
    }

    // ---- Story 2 AC-2/AC-3/AC-4: a valid move recomputes the whole subtree ----

    @Test
    void moveUpdatesPathIdsAndDepthForFolderAndWholeSubtreeButNotParentIdOfDocuments() {
        UUID oldAncestor = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID newParentAncestor = UUID.randomUUID();
        UUID newParentId = UUID.randomUUID();

        WarehouseFolder folder = folder(folderId, oldAncestor, new UUID[] {oldAncestor}, 2);
        WarehouseFolder child = folder(childId, folderId, new UUID[] {oldAncestor, folderId}, 3);
        WarehouseFolder newParent = folder(newParentId, newParentAncestor, new UUID[] {newParentAncestor}, 2);

        when(repository.findById(folderId)).thenReturn(Optional.of(folder));
        when(repository.findById(newParentId)).thenReturn(Optional.of(newParent));
        when(repository.findDescendants(folderId)).thenReturn(new ArrayList<>(List.of(child)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        WarehouseFolder moved = service.moveFolder(admin, folderId, newParentId);

        assertThat(moved.getParentId()).isEqualTo(newParentId);
        assertThat(moved.getPathIds()).containsExactly(newParentAncestor, newParentId);
        assertThat(moved.getDepth()).isEqualTo((short) 3);

        // Descendant keeps its position relative to folder ("head" replaced, "tail" kept).
        assertThat(child.getPathIds()).containsExactly(newParentAncestor, newParentId, folderId);
        assertThat(child.getDepth()).isEqualTo((short) 4);
        assertThat(child.getParentId()).isEqualTo(folderId); // unchanged - only folder itself re-parents

        // moveFolder never touches Document rows - folder_id stays whatever it already was.
        verify(documentRepository, never()).existsByFolderIdAndStatusAndDeletedAtIsNull(any(), any());
    }

    @Test
    void moveToRootClearsParentIdAndPath() {
        UUID folderId = UUID.randomUUID();
        WarehouseFolder folder = folder(folderId, UUID.randomUUID(), randomPath(2), 3);
        when(repository.findById(folderId)).thenReturn(Optional.of(folder));
        when(repository.findDescendants(folderId)).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WarehouseFolder moved = service.moveFolder(admin, folderId, null);

        assertThat(moved.getParentId()).isNull();
        assertThat(moved.getPathIds()).isEmpty();
        assertThat(moved.getDepth()).isEqualTo((short) 1);
    }

    // ---- Story 3: delete only blocked by child folders or ACTIVE documents -

    @Test
    void deleteThrowsNotFoundWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteFolder(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteThrowsFolderNotEmptyWithHasChildFoldersReason() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(folder(id, null, new UUID[0], 1)));
        when(repository.existsByParentId(id)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteFolder(id))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(codeOf(ex)).isEqualTo("FOLDER_NOT_EMPTY");
                    assertThat(reasonOf(ex)).isEqualTo("hasChildFolders");
                });
        verify(repository, never()).deleteById(any());
    }

    @Test
    void deleteThrowsFolderNotEmptyWithHasActiveDocumentsReason() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(folder(id, null, new UUID[0], 1)));
        when(repository.existsByParentId(id)).thenReturn(false);
        when(documentRepository.existsByFolderIdAndStatusAndDeletedAtIsNull(id, DocumentStatus.CLEAN))
                .thenReturn(true);

        assertThatThrownBy(() -> service.deleteFolder(id))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(codeOf(ex)).isEqualTo("FOLDER_NOT_EMPTY");
                    assertThat(reasonOf(ex)).isEqualTo("hasActiveDocuments");
                });
        verify(repository, never()).deleteById(any());
    }

    /**
     * Covers both: a folder that only has rejected/infected documents, and
     * one that only has soft-deleted ('clean' + deleted_at set) documents -
     * both make {@code existsByFolderIdAndStatusAndDeletedAtIsNull} return
     * false (the query itself is exercised for real in
     * {@code WarehouseControllerIT}), so both must delete successfully
     * (Story 3 AC-1/AC-2).
     */
    @Test
    void deleteSucceedsWhenOnlyInactiveOrTrashedDocumentsRemain() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(folder(id, null, new UUID[0], 1)));
        when(repository.existsByParentId(id)).thenReturn(false);
        when(documentRepository.existsByFolderIdAndStatusAndDeletedAtIsNull(eq(id), eq(DocumentStatus.CLEAN)))
                .thenReturn(false);

        service.deleteFolder(id);

        verify(repository).deleteById(id);
    }

    // ---- file 02 §4.4: snapshot deleted_folder_path_ids before the folder disappears -

    @Test
    void deleteSnapshotsAncestorPathOntoEveryDocumentFiledHereRegardlessOfStatus() {
        UUID id = UUID.randomUUID();
        UUID[] ancestors = randomPath(2);
        WarehouseFolder toDelete = folder(id, ancestors[ancestors.length - 1], ancestors, 3);
        when(repository.findById(id)).thenReturn(Optional.of(toDelete));
        when(repository.existsByParentId(id)).thenReturn(false);
        when(documentRepository.existsByFolderIdAndStatusAndDeletedAtIsNull(eq(id), eq(DocumentStatus.CLEAN)))
                .thenReturn(false);

        com.aismartstore.backend.documents.Document alreadySnapshotted =
                new com.aismartstore.backend.documents.Document();
        UUID[] preExisting = new UUID[] {UUID.randomUUID()};
        alreadySnapshotted.setDeletedFolderPathIds(preExisting);
        com.aismartstore.backend.documents.Document freshDocument =
                new com.aismartstore.backend.documents.Document();
        when(documentRepository.findByFolderId(id)).thenReturn(List.of(alreadySnapshotted, freshDocument));

        service.deleteFolder(id);

        UUID[] expected = append(ancestors, id);
        assertThat(freshDocument.getDeletedFolderPathIds()).isEqualTo(expected);
        // Never overwrites a snapshot already set by an earlier, higher-level folder deletion.
        assertThat(alreadySnapshotted.getDeletedFolderPathIds()).isEqualTo(preExisting);
        verify(documentRepository).saveAll(List.of(alreadySnapshotted, freshDocument));
    }
}
