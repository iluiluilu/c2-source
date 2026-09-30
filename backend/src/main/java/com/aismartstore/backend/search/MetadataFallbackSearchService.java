package com.aismartstore.backend.search;

import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentSpecifications;
import com.aismartstore.backend.groups.BusinessDataScope;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.search.dto.SearchFilters;
import com.aismartstore.backend.search.dto.SearchHit;
import com.aismartstore.backend.search.dto.SearchResponse;
import com.aismartstore.backend.tags.DocumentTag;
import com.aismartstore.backend.tags.DocumentTagRepository;
import com.aismartstore.backend.tags.Tag;
import com.aismartstore.backend.tags.TagKinds;
import com.aismartstore.backend.tags.TagRepository;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * search-fulltext-02-tim-kiem-nang-cao.md §4 step 3 (AC-14 story {@code -02},
 * AC-7 story {@code -04}): the "lọc thuần metadata" path used when {@code
 * query} is empty and OpenSearch itself is unreachable - a plain {@code
 * Specification<Document>} query, scoped exactly like every other read
 * ({@link BusinessDataScope}), with no full-text component at all (there is
 * no Vietnamese-equivalent full-text search in Postgres, so a non-empty
 * {@code query} never reaches this class - {@code SearchService} keeps that
 * case degraded instead, per {@code -01} §4 step 2).
 *
 * <p>Never collapses/returns {@code inner_hits} (no segment content to show
 * without OpenSearch) - every {@link SearchHit#segments()} is empty and
 * {@code degradedMode = "opensearch_unavailable"}.
 */
@Component
public class MetadataFallbackSearchService {

    private final DocumentRepository documentRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final WarehouseFolderRepository warehouseFolderRepository;
    private final TagRepository tagRepository;
    private final DocumentTagRepository documentTagRepository;

    public MetadataFallbackSearchService(
            DocumentRepository documentRepository,
            GroupMemberRepository groupMemberRepository,
            WarehouseFolderRepository warehouseFolderRepository,
            TagRepository tagRepository,
            DocumentTagRepository documentTagRepository) {
        this.documentRepository = documentRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.warehouseFolderRepository = warehouseFolderRepository;
        this.tagRepository = tagRepository;
        this.documentTagRepository = documentTagRepository;
    }

    /**
     * @param filters           may be {@code null}/empty only if the caller
     *                          already guaranteed at least one is non-empty
     *                          overall (query is always blank here - {@code
     *                          SearchService} never calls this otherwise)
     * @param effectiveGroupIds already {@code filters.groupIds() ∩
     *                          ctx.groupIds()} (AC-4) - same value {@code
     *                          SearchService} used for the OpenSearch attempt
     */
    public SearchResponse search(
            SearchFilters filters, ScopeContext ctx, List<String> effectiveGroupIds, int page, int size) {
        Specification<Document> spec =
                Specification.where(BusinessDataScope.forDocuments(ctx.userSub(), groupMemberRepository));

        if (filters != null) {
            spec = applyDocType(spec, filters.docType());
            spec = applyTopicTags(spec, filters.topicTags(), filters.resolvedTopicTagsMode());
            if (filters.uploadedFrom() != null || filters.uploadedTo() != null) {
                spec = spec.and(MetadataFilterSpecifications.uploadedBetween(filters.uploadedFrom(), filters.uploadedTo()));
            }
            if (effectiveGroupIds != null && !effectiveGroupIds.isEmpty()) {
                List<UUID> groupIds = effectiveGroupIds.stream().map(UUID::fromString).toList();
                spec = spec.and(MetadataFilterSpecifications.sharedToAnyGroup(groupIds));
            }
            if (filters.folderId() != null) {
                spec = spec.and(MetadataFilterSpecifications.inFolderIds(folderSubtreeIds(filters.folderId())));
            }
            if (filters.mediaType() != null && !filters.mediaType().isEmpty()) {
                spec = spec.and(MetadataFilterSpecifications.hasMediaType(filters.mediaType()));
            }
            if (filters.confirmSource() != null && !filters.confirmSource().isBlank()) {
                spec = spec.and(MetadataFilterSpecifications.hasConfirmSource(filters.confirmSource()));
            }
        }

        // §4 step 1.3: no keyword -> sort=uploaded_at desc always (Document.createdAt is the "uploaded_at" field of record).
        var resultPage = documentRepository.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<Document> documents = resultPage.getContent();
        Map<UUID, String> docTypeLabelByDocumentId = resolveDocTypeLabels(documents.stream().map(Document::getId).toList());

        // search-fulltext-05-frontend.md §2/§3.3: documentDigitizationStatus is
        // not hydrated on this metadata-only fallback path (degraded mode
        // already signals reduced fidelity to the caller) - null here is the
        // same "no evidence either way" value used when the hydrator's own
        // batch lookup finds no document_digitization_status row.
        List<SearchHit> hits = documents.stream()
                .map(d -> new SearchHit(
                        d.getId(), d.getOriginalName(), docTypeLabelByDocumentId.get(d.getId()), 0.0, List.of(), null))
                .toList();

        return new SearchResponse(
                hits, resultPage.getTotalElements(), page, size, SearchResponse.DEGRADED_OPENSEARCH_UNAVAILABLE, List.of());
    }

    /** §4 step 1.2 "doc_type": {@link DocumentSpecifications#hasTagId} on the resolved {@code doc_type} tag id (AC-1). */
    private Specification<Document> applyDocType(Specification<Document> spec, String docTypeLabel) {
        if (docTypeLabel == null || docTypeLabel.isBlank()) {
            return spec;
        }
        return spec.and(tagRepository
                .findByKindAndLabelIgnoreCase(TagKinds.DOC_TYPE, docTypeLabel)
                .map(Tag::getId)
                .map(DocumentSpecifications::hasTagId)
                // SearchService already rejects an unknown docType before this is ever
                // reached (§3.3) - this branch only guards against a race (tag deleted mid-request).
                .orElseGet(MetadataFilterSpecifications::none));
    }

    /** §4 step 1.2 "topic_tags" (AC-2): AND = every tag required, OR = any one of them. */
    private Specification<Document> applyTopicTags(Specification<Document> spec, List<String> labels, String mode) {
        if (labels == null || labels.isEmpty()) {
            return spec;
        }

        List<UUID> tagIds = labels.stream()
                .map(label -> tagRepository.findByKindAndLabelIgnoreCase(TagKinds.TOPIC, label).map(Tag::getId).orElse(null))
                .toList();

        if (SearchFilters.TOPIC_TAGS_MODE_OR.equals(mode)) {
            List<UUID> found = tagIds.stream().filter(Objects::nonNull).toList();
            return found.isEmpty() ? spec.and(MetadataFilterSpecifications.none()) : spec.and(DocumentSpecifications.hasAnyTagId(found));
        }

        // AND: any unresolved label makes the whole group unsatisfiable.
        if (tagIds.contains(null)) {
            return spec.and(MetadataFilterSpecifications.none());
        }
        Specification<Document> result = spec;
        for (UUID tagId : tagIds) {
            result = result.and(DocumentSpecifications.hasTagId(tagId));
        }
        return result;
    }

    /** AC-5: {@code folderId} itself plus every folder strictly under it (up to 5 levels total). */
    private List<UUID> folderSubtreeIds(UUID folderId) {
        List<UUID> ids = new ArrayList<>();
        ids.add(folderId);
        for (WarehouseFolder descendant : warehouseFolderRepository.findDescendants(folderId)) {
            ids.add(descendant.getId());
        }
        return ids;
    }

    /** Batched {@code doc_type} label lookup for the response's {@code SearchHit.docType} - reuses existing repositories, no new query. */
    private Map<UUID, String> resolveDocTypeLabels(List<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> labelByTagId = tagRepository.findByKindOrderByLabelAsc(TagKinds.DOC_TYPE).stream()
                .collect(Collectors.toMap(Tag::getId, Tag::getLabel));

        Map<UUID, String> labelByDocumentId = new HashMap<>();
        for (DocumentTag documentTag : documentTagRepository.findByDocumentIdIn(documentIds)) {
            if (TagKinds.DOC_TYPE.equals(documentTag.getTagKind())) {
                labelByDocumentId.put(documentTag.getDocumentId(), labelByTagId.get(documentTag.getTagId()));
            }
        }
        return labelByDocumentId;
    }
}
