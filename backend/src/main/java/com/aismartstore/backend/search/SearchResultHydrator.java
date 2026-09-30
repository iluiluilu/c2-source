package com.aismartstore.backend.search;

import com.aismartstore.backend.digitization.results.ContentSegment;
import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatus;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatusRepository;
import com.aismartstore.backend.digitization.review.ReviewSegmentRepository;
import com.aismartstore.backend.digitization.review.SourceReferenceView;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.groups.BusinessDataScope;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.search.dto.SearchHit;
import com.aismartstore.backend.search.dto.SegmentHit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * search-fulltext-01-tim-kiem-co-ban.md §4 step 1.6/Task 6: turns raw {@link
 * LexicalHit}s (already scope-filtered once at the OpenSearch layer, §1) into
 * API {@link SearchHit}s, re-checking every document against {@link
 * BusinessDataScope} in Postgres - the "bộ lọc quyết định" (rủi ro Cao SA "A
 * không thấy tài liệu của B"). A document that fails this re-check is
 * dropped from the result entirely, even if OpenSearch said it matched.
 *
 * <p>search-fulltext-05-frontend.md §2/§4 bước 6 (Task 3b): also hydrates
 * {@link SearchHit#documentDigitizationStatus()} and each segment's {@link
 * SegmentHit#sourceReferences()} - both via one batched query each (over the
 * whole page of results, never per document/segment), added right alongside
 * the existing {@code contentSegmentRepository.findAllById} batch above.
 */
@Component
public class SearchResultHydrator {

    private final DocumentRepository documentRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final ContentSegmentRepository contentSegmentRepository;
    private final HighlightMapper highlightMapper;
    private final DocumentDigitizationStatusRepository documentDigitizationStatusRepository;
    private final ReviewSegmentRepository reviewSegmentRepository;

    public SearchResultHydrator(
            DocumentRepository documentRepository,
            GroupMemberRepository groupMemberRepository,
            ContentSegmentRepository contentSegmentRepository,
            HighlightMapper highlightMapper,
            DocumentDigitizationStatusRepository documentDigitizationStatusRepository,
            ReviewSegmentRepository reviewSegmentRepository) {
        this.documentRepository = documentRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.contentSegmentRepository = contentSegmentRepository;
        this.highlightMapper = highlightMapper;
        this.documentDigitizationStatusRepository = documentDigitizationStatusRepository;
        this.reviewSegmentRepository = reviewSegmentRepository;
    }

    /**
     * @param hits lexical hits in relevance/recency order, as returned by
     *             OpenSearch (order is preserved in the result)
     * @param ctx  the same scope the caller already used to filter at the
     *             OpenSearch layer - re-resolved nowhere here, reused as-is
     * @return one {@link SearchHit} per {@code hits} entry whose document
     *         passed the Postgres double-check, in the same order
     */
    public List<SearchHit> hydrate(List<LexicalHit> hits, ScopeContext ctx) {
        if (hits.isEmpty()) {
            return List.of();
        }

        List<UUID> candidateDocumentIds = hits.stream().map(h -> UUID.fromString(h.documentId())).distinct().toList();

        Specification<Document> scopedAndCandidate = Specification
                .where(BusinessDataScope.forDocuments(ctx.userSub(), groupMemberRepository))
                .and((root, query, cb) -> root.get("id").in(candidateDocumentIds));
        Map<UUID, Document> allowedDocumentsById = documentRepository.findAll(scopedAndCandidate).stream()
                .collect(Collectors.toMap(Document::getId, d -> d));

        List<UUID> segmentIds = hits.stream()
                .filter(h -> allowedDocumentsById.containsKey(UUID.fromString(h.documentId())))
                .flatMap(h -> h.segments().stream())
                .map(s -> tryParseUuid(s.source().getSegmentId()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, ContentSegment> segmentsById = segmentIds.isEmpty()
                ? Map.of()
                : contentSegmentRepository.findAllById(segmentIds).stream()
                        .collect(Collectors.toMap(ContentSegment::getId, s -> s));

        // §4 bước 6.1: 1 lô cho toàn bộ danh sách kết quả (đã qua double-check
        // ACL) - thiếu trong map nghĩa là chưa từng có run nào, không phải lỗi.
        Map<UUID, String> digitizationStatusByDocumentId = allowedDocumentsById.isEmpty()
                ? Map.of()
                : documentDigitizationStatusRepository.findAllById(allowedDocumentsById.keySet()).stream()
                        .collect(Collectors.toMap(
                                DocumentDigitizationStatus::getDocumentId, DocumentDigitizationStatus::getOverallStatus));

        // §4 bước 6.2: 1 lô cho toàn bộ segment content_source=summary trong
        // trang kết quả này - không gọi resolveSourceReferences() theo từng segment.
        Map<UUID, SourceReferenceView> sourceReferencesBySourceSegmentId =
                resolveSourceReferences(segmentsById.values());

        List<SearchHit> results = new ArrayList<>();
        for (LexicalHit hit : hits) {
            UUID documentId = UUID.fromString(hit.documentId());
            Document document = allowedDocumentsById.get(documentId);
            if (document == null) {
                // Double-check ACL failed (rủi ro Cao SA) or the document no longer exists - drop, never surface.
                continue;
            }

            List<SegmentHit> segmentHits = hit.segments().stream()
                    .map(s -> {
                        UUID segmentId = tryParseUuid(s.source().getSegmentId());
                        ContentSegment segment = segmentId == null ? null : segmentsById.get(segmentId);
                        return highlightMapper.map(s, segment, sourceReferencesBySourceSegmentId);
                    })
                    .toList();

            results.add(new SearchHit(
                    documentId,
                    document.getOriginalName(),
                    hit.docType(),
                    hit.score(),
                    segmentHits,
                    digitizationStatusByDocumentId.get(documentId)));
        }
        return results;
    }

    /**
     * Bug fix 2026-09-09 (checklist muc 8.16, 3rd bug found while manually
     * verifying search-fulltext-05-frontend - pre-existing in
     * search-fulltext-01/03, not introduced by that work): {@code note}/
     * {@code title} pseudo-segments are indexed with a synthetic id
     * ({@code "{documentId}:note"}/{@code ":title"}, see {@code
     * SearchIndexOutboxPoller#notesSegmentId}/{@code #titleSegmentId}) instead
     * of a real {@code content_segment} UUID. Calling {@code
     * UUID.fromString} on one of those unconditionally throws {@code
     * IllegalArgumentException: UUID string too large}, surfaced by {@code
     * ApiExceptionHandler} as a 400 for the WHOLE search response - not just
     * that one segment - as soon as any hit includes one (e.g. a query that
     * happens to also match a document's filename). A pseudo-id simply has no
     * {@code content_segment} row to hydrate, exactly like an already-correct
     * "not found" lookup.
     */
    private static UUID tryParseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * §4 bước 6.2: union of every {@code source_segment_ids} entry across
     * every {@code content_source="summary"} segment in {@code segments},
     * resolved via {@link ReviewSegmentRepository#resolveSourceReferences} in
     * one call - keyed by the resolved row's own id (i.e. the source
     * segment's id, unique within the result), so {@link HighlightMapper}
     * only needs a map lookup per reference, never another query.
     */
    private Map<UUID, SourceReferenceView> resolveSourceReferences(Collection<ContentSegment> segments) {
        List<UUID> sourceSegmentIds = segments.stream()
                .filter(s -> ContentSegment.SOURCE_SUMMARY.equals(s.getContentSource()))
                .flatMap(s -> HighlightMapper.parseSourceSegmentIds(s.getSourceSegmentIds()).stream())
                .distinct()
                .toList();
        if (sourceSegmentIds.isEmpty()) {
            return Map.of();
        }
        return reviewSegmentRepository.resolveSourceReferences(sourceSegmentIds).stream()
                .collect(Collectors.toMap(
                        ReviewSegmentRepository.SourceReferenceRow::sourceSegmentId,
                        row -> new SourceReferenceView(
                                row.sourceSegmentId(), row.sourceRunId(), row.segmentIndex(), row.timeStartMs(),
                                row.sourceRunReviewState())));
    }
}
