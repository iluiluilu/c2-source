package com.aismartstore.backend.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.digitization.review.ReviewSegmentRepository;
import com.aismartstore.backend.digitization.results.ContentSegment;
import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatus;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatusRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.search.dto.SearchHit;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

/**
 * search-fulltext-05-frontend.md §2/§4 bước 6/§5 (Task 3b) - unit coverage of
 * {@link SearchResultHydrator}'s 2 new fields, on top of the existing ACL
 * double-check ({@code BusinessDataScopeIT}-style Testcontainers tests cover
 * the Specification predicate itself, per {@code SearchScopeIT}'s own note -
 * this suite mocks {@link DocumentRepository#findAll(Specification)} instead
 * so it runs in every environment, same pattern as {@code
 * MetadataFallbackSearchServiceTest}).
 */
class SearchResultHydratorTest {

    private static final String USER_SUB = "user-1";

    private DocumentRepository documentRepository;
    private GroupMemberRepository groupMemberRepository;
    private ContentSegmentRepository contentSegmentRepository;
    private DocumentDigitizationStatusRepository documentDigitizationStatusRepository;
    private ReviewSegmentRepository reviewSegmentRepository;
    private SearchResultHydrator hydrator;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        groupMemberRepository = mock(GroupMemberRepository.class);
        contentSegmentRepository = mock(ContentSegmentRepository.class);
        documentDigitizationStatusRepository = mock(DocumentDigitizationStatusRepository.class);
        reviewSegmentRepository = mock(ReviewSegmentRepository.class);

        when(groupMemberRepository.findGroupIdsByUserSub(USER_SUB)).thenReturn(List.of());

        hydrator = new SearchResultHydrator(
                documentRepository,
                groupMemberRepository,
                contentSegmentRepository,
                new HighlightMapper(),
                documentDigitizationStatusRepository,
                reviewSegmentRepository);
    }

    private static ScopeContext ctx() {
        return new ScopeContext(USER_SUB, List.of());
    }

    private static Document document(UUID id, String name) {
        Document document = new Document();
        setField(document, "id", id);
        document.setOriginalName(name);
        return document;
    }

    private static ContentSegment segment(UUID id, String contentSource) {
        ContentSegment segment = new ContentSegment();
        segment.setId(id);
        segment.setContentSource(contentSource);
        segment.setCurrentText("noi dung");
        return segment;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static LexicalHit lexicalHit(UUID documentId, UUID segmentId, String contentSource) {
        return lexicalHitWithRawSegmentId(documentId, segmentId.toString(), contentSource);
    }

    private static LexicalHit lexicalHitWithRawSegmentId(UUID documentId, String segmentId, String contentSource) {
        SegmentDocument source = new SegmentDocument();
        source.setSegmentId(segmentId);
        source.setDocumentId(documentId.toString());
        source.setContentSource(contentSource);
        source.setConfirmSource("manual");
        return new LexicalHit(
                documentId.toString(), 1.0, null,
                List.of(new LexicalHit.LexicalSegmentHit(source, null)));
    }

    // ---- documentDigitizationStatus: has a row -> its overall_status --------

    @Test
    void hydratesDocumentDigitizationStatusFromAnExistingRow() {
        UUID documentId = UUID.randomUUID();
        UUID segmentId = UUID.randomUUID();
        when(documentRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(document(documentId, "ho-so.pdf")));
        when(contentSegmentRepository.findAllById(anyList())).thenReturn(List.of(segment(segmentId, "ocr")));

        DocumentDigitizationStatus status = new DocumentDigitizationStatus();
        status.setDocumentId(documentId);
        status.setOverallStatus(DocumentDigitizationStatus.STATUS_CONFIRMED);
        when(documentDigitizationStatusRepository.findAllById(any())).thenReturn(List.of(status));

        List<SearchHit> results = hydrator.hydrate(List.of(lexicalHit(documentId, segmentId, "ocr")), ctx());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).documentDigitizationStatus()).isEqualTo("confirmed");
    }

    // ---- documentDigitizationStatus: no row at all -> null, not "not_applicable" ----

    @Test
    void hydratesNullDocumentDigitizationStatusWhenNoRowExistsAtAll() {
        UUID documentId = UUID.randomUUID();
        UUID segmentId = UUID.randomUUID();
        when(documentRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(document(documentId, "ho-so.pdf")));
        when(contentSegmentRepository.findAllById(anyList())).thenReturn(List.of(segment(segmentId, "ocr")));
        when(documentDigitizationStatusRepository.findAllById(any())).thenReturn(List.of());

        List<SearchHit> results = hydrator.hydrate(List.of(lexicalHit(documentId, segmentId, "ocr")), ctx());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).documentDigitizationStatus()).isNull();
    }

    // ---- documentDigitizationStatus: never queried when every hit fails the ACL double-check ----

    @Test
    void neverQueriesDigitizationStatusWhenNoDocumentPassesTheAclDoubleCheck() {
        UUID documentId = UUID.randomUUID();
        UUID segmentId = UUID.randomUUID();
        when(documentRepository.findAll(any(Specification.class))).thenReturn(List.of());

        List<SearchHit> results = hydrator.hydrate(List.of(lexicalHit(documentId, segmentId, "ocr")), ctx());

        assertThat(results).isEmpty();
        verify(documentDigitizationStatusRepository, never()).findAllById(anyList());
    }

    // ---- sourceReferences: summary segment, resolvable source_segment_ids -------------

    @Test
    void hydratesSourceReferencesForASummarySegmentWithResolvableSourceSegmentIds() {
        UUID documentId = UUID.randomUUID();
        UUID summarySegmentId = UUID.randomUUID();
        UUID sourceSegmentId = UUID.randomUUID();
        UUID sourceRunId = UUID.randomUUID();

        when(documentRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(document(documentId, "video.mp4")));
        ContentSegment summarySegment = segment(summarySegmentId, ContentSegment.SOURCE_SUMMARY);
        summarySegment.setSourceSegmentIds(List.of(sourceSegmentId.toString()));
        when(contentSegmentRepository.findAllById(anyList())).thenReturn(List.of(summarySegment));
        when(documentDigitizationStatusRepository.findAllById(any())).thenReturn(List.of());

        ReviewSegmentRepository.SourceReferenceRow row = new ReviewSegmentRepository.SourceReferenceRow(
                sourceSegmentId, sourceRunId, 1, 2000, "confirmed", OffsetDateTime.now(), OffsetDateTime.now());
        when(reviewSegmentRepository.resolveSourceReferences(List.of(sourceSegmentId))).thenReturn(List.of(row));

        List<SearchHit> results =
                hydrator.hydrate(List.of(lexicalHit(documentId, summarySegmentId, "summary")), ctx());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).segments()).hasSize(1);
        assertThat(results.get(0).segments().get(0).sourceReferences()).hasSize(1);
        assertThat(results.get(0).segments().get(0).sourceReferences().get(0).sourceSegmentId())
                .isEqualTo(sourceSegmentId);
        assertThat(results.get(0).segments().get(0).position().type())
                .isEqualTo(com.aismartstore.backend.search.dto.PositionInfo.TYPE_NONE);
    }

    // ---- sourceReferences: called exactly once per hydrate(), never per segment ----

    @Test
    void resolvesSourceReferencesInASingleBatchedCallAcrossMultipleSummarySegments() {
        UUID documentId1 = UUID.randomUUID();
        UUID documentId2 = UUID.randomUUID();
        UUID summarySegmentId1 = UUID.randomUUID();
        UUID summarySegmentId2 = UUID.randomUUID();
        UUID sourceSegmentIdA = UUID.randomUUID();
        UUID sourceSegmentIdB = UUID.randomUUID();

        when(documentRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(document(documentId1, "a.mp4"), document(documentId2, "b.mp4")));
        ContentSegment summary1 = segment(summarySegmentId1, ContentSegment.SOURCE_SUMMARY);
        summary1.setSourceSegmentIds(List.of(sourceSegmentIdA.toString()));
        ContentSegment summary2 = segment(summarySegmentId2, ContentSegment.SOURCE_SUMMARY);
        summary2.setSourceSegmentIds(List.of(sourceSegmentIdB.toString()));
        when(contentSegmentRepository.findAllById(anyList())).thenReturn(List.of(summary1, summary2));
        when(documentDigitizationStatusRepository.findAllById(any())).thenReturn(List.of());
        when(reviewSegmentRepository.resolveSourceReferences(anyList())).thenReturn(List.of());

        hydrator.hydrate(
                List.of(
                        lexicalHit(documentId1, summarySegmentId1, "summary"),
                        lexicalHit(documentId2, summarySegmentId2, "summary")),
                ctx());

        verify(reviewSegmentRepository, org.mockito.Mockito.times(1)).resolveSourceReferences(anyList());
    }

    // ---- sourceReferences: unresolvable source_segment_ids -> null, never throws ----

    @Test
    void summarySegmentWithUnresolvableSourceSegmentIdsGetsNullSourceReferencesInsteadOfThrowing() {
        UUID documentId = UUID.randomUUID();
        UUID summarySegmentId = UUID.randomUUID();
        UUID sourceSegmentId = UUID.randomUUID();

        when(documentRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(document(documentId, "video.mp4")));
        ContentSegment summarySegment = segment(summarySegmentId, ContentSegment.SOURCE_SUMMARY);
        summarySegment.setSourceSegmentIds(List.of(sourceSegmentId.toString()));
        when(contentSegmentRepository.findAllById(anyList())).thenReturn(List.of(summarySegment));
        when(documentDigitizationStatusRepository.findAllById(any())).thenReturn(List.of());
        // Row absent entirely - e.g. the source segment no longer exists.
        when(reviewSegmentRepository.resolveSourceReferences(anyList())).thenReturn(List.of());

        List<SearchHit> results =
                hydrator.hydrate(List.of(lexicalHit(documentId, summarySegmentId, "summary")), ctx());

        assertThat(results.get(0).segments().get(0).sourceReferences()).isNull();
    }

    // ---- pseudo-segment ids (note/title) never crash the whole search ----------

    /**
     * Bug fix 2026-09-09 (checklist muc 8.16, 3rd bug - found manually
     * verifying search-fulltext-05-frontend, pre-existing in
     * search-fulltext-01/03): {@code note}/{@code title} segments are indexed
     * with a synthetic id ({@code "{documentId}:title"}, see {@code
     * SearchIndexOutboxPoller#titleSegmentId}) instead of a real {@code
     * content_segment} UUID - hydrate() used to call {@code
     * UUID.fromString} on every segment id unconditionally, throwing for
     * these and turning the WHOLE search into a 400 as soon as one such
     * pseudo-segment was among the hits (e.g. a query that also happens to
     * match a document's filename).
     */
    @Test
    void pseudoSegmentIdFromATitleOrNoteMatchDoesNotCrashTheWholeHydrate() {
        UUID documentId = UUID.randomUUID();
        String pseudoSegmentId = documentId + ":title";
        when(documentRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(document(documentId, "Huong-dan.pdf")));
        when(documentDigitizationStatusRepository.findAllById(any())).thenReturn(List.of());

        List<SearchHit> results = hydrator.hydrate(
                List.of(lexicalHitWithRawSegmentId(documentId, pseudoSegmentId, "title")), ctx());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).segments()).hasSize(1);
        assertThat(results.get(0).segments().get(0).segmentId()).isEqualTo(pseudoSegmentId);
        assertThat(results.get(0).segments().get(0).position().type())
                .isEqualTo(com.aismartstore.backend.search.dto.PositionInfo.TYPE_NONE);
        // segmentIds ends up empty (the pseudo-id is filtered out) - the
        // batch lookup is skipped entirely (see hydrate()'s isEmpty() branch).
        verify(contentSegmentRepository, never()).findAllById(anyList());
    }

    // ---- sourceReferences: never queried when no summary segment is present ----

    @Test
    void neverQueriesSourceReferencesWhenNoSummarySegmentIsPresent() {
        UUID documentId = UUID.randomUUID();
        UUID segmentId = UUID.randomUUID();
        when(documentRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(document(documentId, "ho-so.pdf")));
        when(contentSegmentRepository.findAllById(anyList())).thenReturn(List.of(segment(segmentId, "ocr")));
        when(documentDigitizationStatusRepository.findAllById(any())).thenReturn(List.of());

        hydrator.hydrate(List.of(lexicalHit(documentId, segmentId, "ocr")), ctx());

        verify(reviewSegmentRepository, never()).resolveSourceReferences(anyList());
    }
}
