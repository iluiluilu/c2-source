package com.aismartstore.backend.search.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.groups.DocumentGroupShare;
import com.aismartstore.backend.groups.DocumentGroupShareRepository;
import com.aismartstore.backend.notes.DocumentNoteRepository;
import com.aismartstore.backend.tags.DocumentTagRepository;
import com.aismartstore.backend.tags.Tag;
import com.aismartstore.backend.tags.TagKinds;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** search-fulltext-04-dong-bo-chi-muc.md §4.2 - {@link DocumentIndexFieldsBuilder}'s Postgres re-read join. */
class DocumentIndexFieldsBuilderTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentTagRepository documentTagRepository = mock(DocumentTagRepository.class);
    private final DocumentGroupShareRepository documentGroupShareRepository = mock(DocumentGroupShareRepository.class);
    private final DocumentNoteRepository documentNoteRepository = mock(DocumentNoteRepository.class);
    private final ContentSegmentRepository contentSegmentRepository = mock(ContentSegmentRepository.class);
    private final WarehouseFolderRepository warehouseFolderRepository = mock(WarehouseFolderRepository.class);

    private final DocumentIndexFieldsBuilder builder = new DocumentIndexFieldsBuilder(
            documentRepository,
            documentTagRepository,
            documentGroupShareRepository,
            documentNoteRepository,
            contentSegmentRepository,
            warehouseFolderRepository);

    @Test
    void returnsEmptyWhenTheDocumentIsGone() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId)).thenReturn(Optional.empty());

        assertThat(builder.build(documentId)).isEmpty();
    }

    @Test
    void returnsEmptyWhenTheDocumentIsSoftDeleted() {
        UUID documentId = UUID.randomUUID();
        Document document = document(documentId);
        document.setDeletedAt(OffsetDateTime.now());
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        assertThat(builder.build(documentId)).isEmpty();
    }

    @Test
    void returnsEmptyWhenTheDocumentIsNotClean() {
        UUID documentId = UUID.randomUUID();
        Document document = document(documentId);
        document.setStatus("rejected");
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        assertThat(builder.build(documentId)).isEmpty();
    }

    @Test
    void buildsDocTypeTopicTagsFolderPathAndScopeGroupIds() {
        UUID documentId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        UUID parentFolderId = UUID.randomUUID();
        UUID groupId1 = UUID.randomUUID();
        UUID groupId2 = UUID.randomUUID();

        Document document = document(documentId);
        document.setFolderId(folderId);
        document.setMimeType("application/pdf");
        document.setUploaderSub("owner-sub");
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        Tag docType = tag(TagKinds.DOC_TYPE, "văn bản");
        Tag topic1 = tag(TagKinds.TOPIC, "tai-chinh");
        Tag topic2 = tag(TagKinds.TOPIC, "hop-dong");
        when(documentTagRepository.findTagsForDocument(documentId)).thenReturn(List.of(docType, topic1, topic2));

        WarehouseFolder folder = new WarehouseFolder();
        folder.setPathIds(new UUID[] {parentFolderId});
        when(warehouseFolderRepository.findById(folderId)).thenReturn(Optional.of(folder));

        DocumentGroupShare share1 = share(documentId, groupId1);
        DocumentGroupShare share2 = share(documentId, groupId2);
        when(documentGroupShareRepository.findByDocumentIdOrderBySharedAtAsc(documentId))
                .thenReturn(List.of(share1, share2));

        DocumentIndexFieldsBuilder.Fields fields = builder.build(documentId).orElseThrow();

        assertThat(fields.docType()).isEqualTo("văn bản");
        assertThat(fields.topicTags()).containsExactlyInAnyOrder("tai-chinh", "hop-dong");
        assertThat(fields.folderId()).isEqualTo(folderId.toString());
        assertThat(fields.folderPathIds()).containsExactly(parentFolderId.toString(), folderId.toString());
        assertThat(fields.mediaType()).isEqualTo("document");
        assertThat(fields.ownerUserId()).isEqualTo("owner-sub");
        assertThat(fields.scopeGroupIds()).containsExactlyInAnyOrder(groupId1.toString(), groupId2.toString());
    }

    @Test
    void docTypeIsNullWhenTheDocumentHasNotBeenClassifiedYet() {
        UUID documentId = UUID.randomUUID();
        Document document = document(documentId);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(documentTagRepository.findTagsForDocument(documentId)).thenReturn(List.of());
        when(warehouseFolderRepository.findById(document.getFolderId())).thenReturn(Optional.empty());
        when(documentGroupShareRepository.findByDocumentIdOrderBySharedAtAsc(documentId)).thenReturn(List.of());

        DocumentIndexFieldsBuilder.Fields fields = builder.build(documentId).orElseThrow();

        assertThat(fields.docType()).isNull();
        assertThat(fields.topicTags()).isEmpty();
        assertThat(fields.scopeGroupIds()).isEmpty();
    }

    /**
     * Bug fix 2026-09-09 (checklist muc 8.16): {@code document.folder_id} is
     * nullable by design (a deleted folder detaches its documents, V9
     * migration) - {@code build()} used to call {@code
     * warehouseFolderRepository.findById(null)} unconditionally, which a
     * real Spring Data repository rejects with {@code
     * InvalidDataAccessApiUsageException}. Asserts both the fixed output
     * shape AND that the repository is never even called with a null id.
     */
    @Test
    void folderIdAndFolderPathIdsAreEmptyWhenTheDocumentHasNoFolder() {
        UUID documentId = UUID.randomUUID();
        Document document = document(documentId);
        document.setFolderId(null);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(documentTagRepository.findTagsForDocument(documentId)).thenReturn(List.of());
        when(documentGroupShareRepository.findByDocumentIdOrderBySharedAtAsc(documentId)).thenReturn(List.of());

        DocumentIndexFieldsBuilder.Fields fields = builder.build(documentId).orElseThrow();

        assertThat(fields.folderId()).isNull();
        assertThat(fields.folderPathIds()).isEmpty();
        verifyNoInteractions(warehouseFolderRepository);
    }

    /**
     * bugs/bug-002-search-index-version-conflict.md mục 5.2/Phát hiện 4 -
     * {@code contentVersion} falls back to {@code document.createdAt} (as
     * epoch-microseconds) when the document has no tag/share/note/segment
     * yet - the floor value, never null even for a brand-new document.
     */
    @Test
    void contentVersionFallsBackToDocumentCreatedAtWhenNoOtherSourceExists() {
        UUID documentId = UUID.randomUUID();
        Document document = document(documentId);
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-09-21T10:00:00Z");
        document.setCreatedAt(createdAt);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(documentTagRepository.findTagsForDocument(documentId)).thenReturn(List.of());
        when(warehouseFolderRepository.findById(document.getFolderId())).thenReturn(Optional.empty());
        when(documentGroupShareRepository.findByDocumentIdOrderBySharedAtAsc(documentId)).thenReturn(List.of());
        // documentTagRepository/documentGroupShareRepository/documentNoteRepository/
        // contentSegmentRepository's findMax*ByDocumentId methods are left unstubbed:
        // Mockito's default answer returns Optional.empty() for unstubbed Optional-returning
        // methods, exactly the "no row yet" case this test wants.

        DocumentIndexFieldsBuilder.Fields fields = builder.build(documentId).orElseThrow();

        assertThat(fields.contentVersion()).isEqualTo(epochMicros(createdAt));
    }

    /**
     * bugs/bug-002-search-index-version-conflict.md mục 5.2/Phát hiện 2 -
     * {@code contentVersion} must be the GREATEST across all 5 sources, not
     * just document/tag/share (the formula this test locks in was missing
     * {@code content_segment.updated_at} in its first draft - this is the
     * regression test for that specific gap).
     */
    @Test
    void contentVersionIsTheGreatestAcrossAllFiveSources() {
        UUID documentId = UUID.randomUUID();
        Document document = document(documentId);
        document.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(documentTagRepository.findTagsForDocument(documentId)).thenReturn(List.of());
        when(warehouseFolderRepository.findById(document.getFolderId())).thenReturn(Optional.empty());
        when(documentGroupShareRepository.findByDocumentIdOrderBySharedAtAsc(documentId)).thenReturn(List.of());

        OffsetDateTime tagAssignedAt = OffsetDateTime.parse("2026-09-01T00:00:00Z");
        OffsetDateTime shareSharedAt = OffsetDateTime.parse("2026-09-10T00:00:00Z");
        OffsetDateTime noteUpdatedAt = OffsetDateTime.parse("2026-09-15T00:00:00Z");
        OffsetDateTime segmentUpdatedAt = OffsetDateTime.parse("2026-09-20T12:00:00Z"); // the greatest
        when(documentTagRepository.findMaxAssignedAtByDocumentId(documentId)).thenReturn(Optional.of(tagAssignedAt));
        when(documentGroupShareRepository.findMaxSharedAtByDocumentId(documentId))
                .thenReturn(Optional.of(shareSharedAt));
        when(documentNoteRepository.findMaxUpdatedAtByDocumentId(documentId)).thenReturn(Optional.of(noteUpdatedAt));
        when(contentSegmentRepository.findMaxUpdatedAtByDocumentId(documentId))
                .thenReturn(Optional.of(segmentUpdatedAt));

        DocumentIndexFieldsBuilder.Fields fields = builder.build(documentId).orElseThrow();

        assertThat(fields.contentVersion()).isEqualTo(epochMicros(segmentUpdatedAt));
    }

    private static long epochMicros(OffsetDateTime value) {
        java.time.Instant instant = value.toInstant();
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }

    private static Document document(UUID id) {
        Document document = new Document();
        document.setOriginalName("file.pdf");
        document.setMimeType("application/pdf");
        document.setFolderId(UUID.randomUUID());
        document.setUploaderSub("owner-sub");
        document.setUploaderUsername("owner");
        document.setStatus("clean");
        document.setCreatedAt(OffsetDateTime.now());
        try {
            java.lang.reflect.Field idField = Document.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(document, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return document;
    }

    private static Tag tag(String kind, String label) {
        Tag tag = new Tag();
        tag.setKind(kind);
        tag.setLabel(label);
        return tag;
    }

    private static DocumentGroupShare share(UUID documentId, UUID groupId) {
        DocumentGroupShare share = new DocumentGroupShare();
        share.setDocumentId(documentId);
        share.setGroupId(groupId);
        share.setSharedBySub("sharer-sub");
        share.setSharedAt(OffsetDateTime.now());
        return share;
    }
}
