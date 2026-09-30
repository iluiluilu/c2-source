package com.aismartstore.backend.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.search.dto.SearchFilters;
import com.aismartstore.backend.search.dto.SearchHit;
import com.aismartstore.backend.search.dto.SearchResponse;
import com.aismartstore.backend.tags.DocumentTag;
import com.aismartstore.backend.tags.DocumentTagRepository;
import com.aismartstore.backend.tags.Tag;
import com.aismartstore.backend.tags.TagKinds;
import com.aismartstore.backend.tags.TagRepository;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * search-fulltext-02-tim-kiem-nang-cao.md §4 step 3/§5 - {@link
 * MetadataFallbackSearchService} unit coverage that does not need a real
 * Postgres (Specification predicate correctness is exercised by {@code
 * SearchScopeIT}-style Testcontainers tests instead, per the module's V21
 * known-issue note): response shape (AC-14, "segments always empty",
 * degradedMode), sort order, and the doc_type label resolution/race-guard.
 */
class MetadataFallbackSearchServiceTest {

    private static final String USER_SUB = "user-1";

    private DocumentRepository documentRepository;
    private GroupMemberRepository groupMemberRepository;
    private WarehouseFolderRepository warehouseFolderRepository;
    private TagRepository tagRepository;
    private DocumentTagRepository documentTagRepository;
    private MetadataFallbackSearchService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        groupMemberRepository = mock(GroupMemberRepository.class);
        warehouseFolderRepository = mock(WarehouseFolderRepository.class);
        tagRepository = mock(TagRepository.class);
        documentTagRepository = mock(DocumentTagRepository.class);

        when(groupMemberRepository.findGroupIdsByUserSub(USER_SUB)).thenReturn(List.of());

        service = new MetadataFallbackSearchService(
                documentRepository, groupMemberRepository, warehouseFolderRepository, tagRepository, documentTagRepository);
    }

    private static ScopeContext ctx() {
        return new ScopeContext(USER_SUB, List.of());
    }

    private static Document document(String name) {
        Document document = new Document();
        document.setOriginalName(name);
        document.setMimeType("application/pdf");
        document.setCreatedAt(OffsetDateTime.now());
        return document;
    }

    // ---- AC-14: never collapses/highlights - every hit's segments stay empty, degradedMode is set ----

    @Test
    void returnsHitsWithEmptySegmentsAndTheOpenSearchUnavailableDegradedMode() {
        Document document = document("ho-so.pdf");
        when(documentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(document), Pageable.ofSize(20), 1));
        when(documentTagRepository.findByDocumentIdIn(anyList())).thenReturn(List.of());
        when(tagRepository.findByKindOrderByLabelAsc(TagKinds.DOC_TYPE)).thenReturn(List.of());

        SearchResponse response = service.search(null, ctx(), List.of(), 0, 20);

        assertThat(response.degradedMode()).isEqualTo(SearchResponse.DEGRADED_OPENSEARCH_UNAVAILABLE);
        assertThat(response.totalApprox()).isEqualTo(1);
        assertThat(response.results()).hasSize(1);
        SearchHit hit = response.results().get(0);
        assertThat(hit.documentName()).isEqualTo("ho-so.pdf");
        assertThat(hit.segments()).isEmpty();
        assertThat(hit.score()).isZero();
    }

    // ---- §4 step 1.3: no keyword -> always sort=uploaded_at (createdAt) desc ------------------------

    @Test
    void alwaysSortsByCreatedAtDescending() {
        when(documentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(documentTagRepository.findByDocumentIdIn(anyList())).thenReturn(List.of());

        service.search(null, ctx(), List.of(), 0, 20);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(documentRepository).findAll(any(Specification.class), pageableCaptor.capture());
        Sort.Order order = pageableCaptor.getValue().getSort().getOrderFor("createdAt");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    // ---- AC-1: doc_type label resolved for the response, batched via existing repositories ---------

    @Test
    void resolvesTheDocTypeLabelForEachHitFromExistingRepositories() {
        Document document = document("anh.jpg");
        UUID documentId = UUID.randomUUID();
        UUID tagId = UUID.randomUUID();
        setId(document, documentId);

        Tag docTypeTag = new Tag();
        setTagId(docTypeTag, tagId);
        docTypeTag.setKind(TagKinds.DOC_TYPE);
        docTypeTag.setLabel("hình ảnh");

        DocumentTag link = new DocumentTag();
        link.setDocumentId(documentId);
        link.setTagId(tagId);
        link.setTagKind(TagKinds.DOC_TYPE);

        when(documentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(document)));
        when(tagRepository.findByKindOrderByLabelAsc(TagKinds.DOC_TYPE)).thenReturn(List.of(docTypeTag));
        when(documentTagRepository.findByDocumentIdIn(anyList())).thenReturn(List.of(link));

        SearchResponse response = service.search(null, ctx(), List.of(), 0, 20);

        assertThat(response.results().get(0).docType()).isEqualTo("hình ảnh");
    }

    // ---- Race guard: a docType filter whose tag disappeared mid-request matches nothing, never throws ----

    @Test
    void anUnresolvableDocTypeFilterMatchesNothingInsteadOfThrowing() {
        when(tagRepository.findByKindAndLabelIgnoreCase(TagKinds.DOC_TYPE, "khong-ton-tai")).thenReturn(Optional.empty());
        when(documentRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        SearchFilters filters = new SearchFilters("khong-ton-tai", null, null, null, null, null, null, null, null);

        SearchResponse response = service.search(filters, ctx(), List.of(), 0, 20);

        assertThat(response.results()).isEmpty();
    }

    private static void setId(Document document, UUID id) {
        try {
            var field = Document.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(document, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setTagId(Tag tag, UUID id) {
        try {
            var field = Tag.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(tag, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
