package com.aismartstore.backend.search;

import com.aismartstore.backend.digitization.results.ContentSegment;
import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatus;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatusRepository;
import com.aismartstore.backend.digitization.review.ReviewSegmentRepository;
import com.aismartstore.backend.digitization.review.SourceReferenceView;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentSpecifications;
import com.aismartstore.backend.groups.BusinessDataScope;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.notes.DocumentNote;
import com.aismartstore.backend.notes.DocumentNoteRepository;
import com.aismartstore.backend.search.dto.PositionInfo;
import com.aismartstore.backend.search.dto.SemanticResult;
import com.aismartstore.backend.tags.DocumentTag;
import com.aismartstore.backend.tags.DocumentTagRepository;
import com.aismartstore.backend.tags.Tag;
import com.aismartstore.backend.tags.TagKinds;
import com.aismartstore.backend.tags.TagRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * SPEC-search-semantic T15 (AC-2, AC-2.1, AC-2.5, AC-4.3, AC-8): turns fused candidates into API
 * {@link SemanticResult}s. This is the "bộ lọc quyết định": every document is re-checked against
 * {@link BusinessDataScope} in PostgreSQL (the index ACL is only the first filter), the excerpt is
 * read from {@code content_segment.current_text} (QĐ 4, AC-2.4 - never from the index), a segment
 * that no longer exists / was soft-deleted / is {@code sed} is dropped, and the order of the input
 * (RRF) is preserved.
 *
 * <p>{@code title}/{@code note} segments have synthetic ids ({@code <documentId>:title|note}) with
 * no {@code content_segment} row: the excerpt comes from {@code document.original_name} / the merged
 * notes, position is {@code none}.
 */
@Component
public class SemanticResultHydrator {

    private static final String TITLE_SUFFIX = ":title";
    private static final String NOTE_SUFFIX = ":note";

    private final DocumentRepository documentRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final ContentSegmentRepository contentSegmentRepository;
    private final DigitizationRunRepository digitizationRunRepository;
    private final DocumentDigitizationStatusRepository documentDigitizationStatusRepository;
    private final ReviewSegmentRepository reviewSegmentRepository;
    private final DocumentNoteRepository documentNoteRepository;
    private final DocumentTagRepository documentTagRepository;
    private final TagRepository tagRepository;
    private final HighlightMapper highlightMapper;

    public SemanticResultHydrator(
            DocumentRepository documentRepository,
            GroupMemberRepository groupMemberRepository,
            ContentSegmentRepository contentSegmentRepository,
            DigitizationRunRepository digitizationRunRepository,
            DocumentDigitizationStatusRepository documentDigitizationStatusRepository,
            ReviewSegmentRepository reviewSegmentRepository,
            DocumentNoteRepository documentNoteRepository,
            DocumentTagRepository documentTagRepository,
            TagRepository tagRepository,
            HighlightMapper highlightMapper) {
        this.documentRepository = documentRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.contentSegmentRepository = contentSegmentRepository;
        this.digitizationRunRepository = digitizationRunRepository;
        this.documentDigitizationStatusRepository = documentDigitizationStatusRepository;
        this.reviewSegmentRepository = reviewSegmentRepository;
        this.documentNoteRepository = documentNoteRepository;
        this.documentTagRepository = documentTagRepository;
        this.tagRepository = tagRepository;
        this.highlightMapper = highlightMapper;
    }

    public List<SemanticResult> hydrate(List<FusedHit> hits, ScopeContext ctx) {
        if (hits.isEmpty()) {
            return List.of();
        }

        List<UUID> candidateDocumentIds = hits.stream().map(FusedHit::documentId).distinct().toList();
        Specification<Document> scopedAndCandidate = Specification
                .where(BusinessDataScope.forDocuments(ctx.userSub(), groupMemberRepository))
                .and(DocumentSpecifications.isClean())
                .and((root, query, cb) -> root.get("id").in(candidateDocumentIds));
        Map<UUID, Document> allowedDocuments = documentRepository.findAll(scopedAndCandidate).stream()
                .collect(Collectors.toMap(Document::getId, d -> d));
        if (allowedDocuments.isEmpty()) {
            return List.of();
        }

        List<UUID> contentSegmentIds = hits.stream()
                .filter(h -> allowedDocuments.containsKey(h.documentId()))
                .map(h -> tryParseUuid(h.segmentId()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, ContentSegment> segmentsById = contentSegmentIds.isEmpty()
                ? Map.of()
                : contentSegmentRepository.findAllById(contentSegmentIds).stream()
                        .collect(Collectors.toMap(ContentSegment::getId, s -> s));

        Map<UUID, String> digitizationStatus = documentDigitizationStatusRepository
                .findAllById(allowedDocuments.keySet()).stream()
                .collect(Collectors.toMap(
                        DocumentDigitizationStatus::getDocumentId, DocumentDigitizationStatus::getOverallStatus));
        Map<UUID, String> runConfirmSources = resolveRunConfirmSources(segmentsById.values());
        Map<UUID, SourceReferenceView> sourceRefs = resolveSourceReferences(segmentsById.values());
        Map<UUID, String> docTypes = resolveDocTypes(allowedDocuments.keySet());

        List<SemanticResult> results = new ArrayList<>();
        for (FusedHit hit : hits) {
            Document document = allowedDocuments.get(hit.documentId());
            if (document == null) {
                continue; // ACL double-check failed or document gone - never surfaced (AC-4.3)
            }
            SemanticResult result =
                    toResult(hit, document, segmentsById, runConfirmSources, digitizationStatus, sourceRefs, docTypes);
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }

    private SemanticResult toResult(
            FusedHit hit,
            Document document,
            Map<UUID, ContentSegment> segmentsById,
            Map<UUID, String> runConfirmSources,
            Map<UUID, String> digitizationStatus,
            Map<UUID, SourceReferenceView> sourceRefs,
            Map<UUID, String> docTypes) {
        UUID documentId = document.getId();
        String segmentId = hit.segmentId();

        String excerpt;
        String contentSource;
        String confirmSource = null;
        PositionInfo position;
        List<SourceReferenceView> references = null;

        UUID contentSegmentId = tryParseUuid(segmentId);
        if (contentSegmentId != null) {
            ContentSegment segment = segmentsById.get(contentSegmentId);
            if (segment == null || segment.isDeleted() || !documentId.equals(segment.getDocumentId())) {
                return null;
            }
            if (ContentSegment.SOURCE_SED.equals(segment.getContentSource())) {
                return null; // AC-2.5
            }
            excerpt = segment.getCurrentText();
            contentSource = segment.getContentSource();
            // AC-2.1: the run's confirm_source (manual vs auto confirmation), not whether the segment was edited.
            confirmSource = DigitizationRun.searchConfirmSource(runConfirmSources.get(segment.getRunId()));
            position = highlightMapper.positionOf(segment);
            if (ContentSegment.SOURCE_SUMMARY.equals(segment.getContentSource())) {
                references = referencesOf(segment, sourceRefs);
            }
        } else if (segmentId.equals(documentId + TITLE_SUFFIX)) {
            excerpt = document.getOriginalName();
            contentSource = "title";
            position = none();
        } else if (segmentId.equals(documentId + NOTE_SUFFIX)) {
            excerpt = mergedNotes(documentId);
            if (excerpt == null) {
                return null;
            }
            contentSource = "note";
            position = none();
        } else {
            return null;
        }
        if (excerpt == null || excerpt.isBlank()) {
            return null;
        }

        return new SemanticResult(
                segmentId,
                documentId.toString(),
                document.getOriginalName(),
                docTypes.get(documentId),
                excerpt,
                contentSource,
                confirmSource,
                position,
                hit.matchedLexical(),
                hit.cosine(),
                hit.rrf(),
                digitizationStatus.get(documentId),
                references);
    }

    private String mergedNotes(UUID documentId) {
        List<DocumentNote> notes = new ArrayList<>(documentNoteRepository.findByDocumentIdOrderByCreatedAtDesc(documentId));
        if (notes.isEmpty()) {
            return null;
        }
        java.util.Collections.reverse(notes); // oldest first, same merge as the note segment indexer
        return notes.stream().map(DocumentNote::getBody).collect(Collectors.joining("\n\n"));
    }

    private Map<UUID, String> resolveDocTypes(Collection<UUID> documentIds) {
        List<DocumentTag> links = documentTagRepository.findByDocumentIdIn(new ArrayList<>(documentIds)).stream()
                .filter(l -> TagKinds.DOC_TYPE.equals(l.getTagKind()))
                .toList();
        if (links.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> labelByTagId = tagRepository
                .findAllById(links.stream().map(DocumentTag::getTagId).distinct().toList()).stream()
                .collect(Collectors.toMap(Tag::getId, Tag::getLabel));
        Map<UUID, String> result = new java.util.HashMap<>();
        for (DocumentTag link : links) {
            String label = labelByTagId.get(link.getTagId());
            if (label != null) {
                result.put(link.getDocumentId(), label);
            }
        }
        return result;
    }

    /** runId -> DB confirm_source of every run the hydrated content segments belong to. */
    private Map<UUID, String> resolveRunConfirmSources(Collection<ContentSegment> segments) {
        List<UUID> runIds = segments.stream().map(ContentSegment::getRunId).distinct().toList();
        if (runIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> out = new HashMap<>();
        for (DigitizationRun run : digitizationRunRepository.findAllById(runIds)) {
            out.put(run.getId(), run.getConfirmSource());
        }
        return out;
    }

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

    private static List<SourceReferenceView> referencesOf(ContentSegment segment, Map<UUID, SourceReferenceView> sourceRefs) {
        List<SourceReferenceView> resolved = new ArrayList<>();
        for (UUID id : HighlightMapper.parseSourceSegmentIds(segment.getSourceSegmentIds())) {
            SourceReferenceView view = sourceRefs.get(id);
            if (view != null) {
                resolved.add(view);
            }
        }
        return resolved.isEmpty() ? null : resolved;
    }

    private static PositionInfo none() {
        return new PositionInfo(PositionInfo.TYPE_NONE, null, null, null, null, null, null, null, null);
    }

    private static UUID tryParseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
