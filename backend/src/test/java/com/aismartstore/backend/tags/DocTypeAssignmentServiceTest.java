package com.aismartstore.backend.tags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.scanning.AllowedFileFormat;
import com.aismartstore.backend.scanning.AllowedFileFormatRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage of classification-notes-01-tags.md §5 ("Unit test -
 * DocTypeAssignmentService"). The "xuất hiện ở danh sách cần xử lý thủ công"
 * half of the MIME-unmapped case is a DB query
 * ({@code DocumentTagRepository#findDocumentIdsMissingDocType}/{@code
 * DocumentSpecifications#missingDocType}), covered by {@code
 * TagsControllerIT} instead of here.
 */
class DocTypeAssignmentServiceTest {

    private AllowedFileFormatRepository allowedFileFormatRepository;
    private TagRepository tagRepository;
    private DocumentTagRepository documentTagRepository;
    private DocumentRepository documentRepository;
    private OutboxEventRepository outboxEventRepository;
    private DocTypeAssignmentService service;

    @BeforeEach
    void setUp() {
        allowedFileFormatRepository = mock(AllowedFileFormatRepository.class);
        tagRepository = mock(TagRepository.class);
        documentTagRepository = mock(DocumentTagRepository.class);
        documentRepository = mock(DocumentRepository.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        service = new DocTypeAssignmentService(
                allowedFileFormatRepository, tagRepository, documentTagRepository, documentRepository,
                outboxEventRepository);
    }

    private static Document document(UUID id, String mimeType) {
        Document document = new Document();
        document.setOriginalName("f.pdf");
        document.setMimeType(mimeType);
        document.setSize(1);
        document.setFolderId(UUID.randomUUID());
        document.setUploaderSub("u");
        document.setUploaderUsername("u");
        document.setStatus("clean");
        try {
            var field = Document.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(document, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return document;
    }

    private static AllowedFileFormat format(String mimeType, String category) {
        AllowedFileFormat format = new AllowedFileFormat();
        format.setMimeType(mimeType);
        format.setCategory(category);
        format.setExtensions(new String[] {"bin"});
        return format;
    }

    // ---- MIME map được -> đúng 1 nhãn ---------------------------------------

    @Test
    void aMappableMimeTypeAssignsExactlyOneDocTypeTag() {
        UUID documentId = UUID.randomUUID();
        Document document = document(documentId, "application/pdf");
        UUID tagId = UUID.randomUUID();
        Tag tag = new Tag();
        tag.setKind(TagKinds.DOC_TYPE);
        tag.setLabel("văn bản");

        when(allowedFileFormatRepository.findById("application/pdf"))
                .thenReturn(Optional.of(format("application/pdf", "TEXT_DOC")));
        when(tagRepository.findByKindAndLabelIgnoreCase(TagKinds.DOC_TYPE, "văn bản")).thenReturn(Optional.of(tag));

        boolean assigned = service.assignOnIngest(document, "uploader-1", "uploader-1");

        assertThat(assigned).isTrue();
        verify(documentTagRepository)
                .insert(eq(documentId), any(), eq(TagKinds.DOC_TYPE), eq("uploader-1"), eq("uploader-1"));
        verify(outboxEventRepository).save(any());
    }

    // ---- MIME không map được -> không tạo document_tag ----------------------

    @Test
    void anUnmappableMimeTypeDoesNotCreateADocumentTagRow() {
        UUID documentId = UUID.randomUUID();
        Document document = document(documentId, "application/x-unknown");
        when(allowedFileFormatRepository.findById("application/x-unknown")).thenReturn(Optional.empty());

        boolean assigned = service.assignOnIngest(document, "uploader-1", "uploader-1");

        assertThat(assigned).isFalse();
        verify(documentTagRepository, never()).insert(any(), any(), any(), any(), any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void resolveDocTypeTagReturnsEmptyWhenTheAllowlistCategoryHasNoSeedTag() {
        // Defensive case: category exists in the allowlist but the expected seed
        // row is somehow missing (should never happen given V20's seed insert).
        when(allowedFileFormatRepository.findById("image/png")).thenReturn(Optional.of(format("image/png", "IMAGE")));
        when(tagRepository.findByKindAndLabelIgnoreCase(TagKinds.DOC_TYPE, "hình ảnh")).thenReturn(Optional.empty());

        assertThat(service.resolveDocTypeTag("image/png")).isEmpty();
    }

    // ---- backfillPage: 1 trang gồm cả map được lẫn không map được -----------

    @Test
    void backfillPageAssignsMappableDocumentsAndLeavesUnmappableOnesUntouched() {
        UUID mappableId = UUID.randomUUID();
        UUID unmappableId = UUID.randomUUID();
        when(documentTagRepository.findDocumentIdsMissingDocType(500)).thenReturn(List.of(mappableId, unmappableId));

        Document mappable = document(mappableId, "image/png");
        Document unmappable = document(unmappableId, "application/x-unknown");
        when(documentRepository.findById(mappableId)).thenReturn(Optional.of(mappable));
        when(documentRepository.findById(unmappableId)).thenReturn(Optional.of(unmappable));

        Tag tag = new Tag();
        tag.setKind(TagKinds.DOC_TYPE);
        tag.setLabel("hình ảnh");
        when(allowedFileFormatRepository.findById("image/png")).thenReturn(Optional.of(format("image/png", "IMAGE")));
        when(tagRepository.findByKindAndLabelIgnoreCase(TagKinds.DOC_TYPE, "hình ảnh")).thenReturn(Optional.of(tag));
        when(allowedFileFormatRepository.findById("application/x-unknown")).thenReturn(Optional.empty());

        DocTypeAssignmentService.BackfillOutcome outcome = service.backfillPage(500);

        assertThat(outcome.scanned()).isEqualTo(2);
        assertThat(outcome.assigned()).isEqualTo(1);
        assertThat(outcome.stillUnmapped()).isEqualTo(1);
        verify(documentTagRepository).insert(eq(mappableId), any(), eq(TagKinds.DOC_TYPE), any(), any());
        verify(documentTagRepository, never()).insert(eq(unmappableId), any(), any(), any(), any());
    }
}
