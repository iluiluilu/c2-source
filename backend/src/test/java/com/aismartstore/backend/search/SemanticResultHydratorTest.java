package com.aismartstore.backend.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.digitization.results.ContentSegment;
import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatus;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatusRepository;
import com.aismartstore.backend.digitization.review.ReviewSegmentRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.notes.DocumentNote;
import com.aismartstore.backend.notes.DocumentNoteRepository;
import com.aismartstore.backend.search.dto.SemanticResult;
import com.aismartstore.backend.tags.DocumentTagRepository;
import com.aismartstore.backend.tags.TagRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

/** SPEC-search-semantic T15 (AC-2, AC-2.4, AC-2.5, AC-4.3, AC-8): the Postgres-side "deciding filter". */
class SemanticResultHydratorTest {

    private static final String SUB = "user-a";

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final GroupMemberRepository groupMemberRepository = mock(GroupMemberRepository.class);
    private final ContentSegmentRepository segmentRepository = mock(ContentSegmentRepository.class);
    private final DigitizationRunRepository runRepository = mock(DigitizationRunRepository.class);
    private final DocumentDigitizationStatusRepository statusRepository = mock(DocumentDigitizationStatusRepository.class);
    private final ReviewSegmentRepository reviewSegmentRepository = mock(ReviewSegmentRepository.class);
    private final DocumentNoteRepository noteRepository = mock(DocumentNoteRepository.class);
    private final DocumentTagRepository documentTagRepository = mock(DocumentTagRepository.class);
    private final TagRepository tagRepository = mock(TagRepository.class);
    private SemanticResultHydrator hydrator;

    private final UUID documentId = UUID.randomUUID();
    private final ScopeContext ctx = new ScopeContext(SUB, List.of());

    @BeforeEach
    void setUp() {
        when(groupMemberRepository.findGroupIdsByUserSub(SUB)).thenReturn(List.of());
        hydrator = new SemanticResultHydrator(
                documentRepository, groupMemberRepository, segmentRepository, runRepository, statusRepository, reviewSegmentRepository,
                noteRepository, documentTagRepository, tagRepository, new HighlightMapper());
        when(documentTagRepository.findByDocumentIdIn(anyList())).thenReturn(List.of());
        when(statusRepository.findAllById(anyIterable())).thenReturn(List.of());
    }

    private void postgresAllows(Document... docs) {
        when(documentRepository.findAll(any(Specification.class))).thenReturn(List.of(docs));
    }

    private Document document() {
        Document d = new Document();
        ReflectionTestUtils.setField(d, "id", documentId);
        d.setOriginalName("Quyet-dinh-12.pdf");
        return d;
    }

    private ContentSegment segment(String source, String text, boolean deleted) {
        ContentSegment s = new ContentSegment();
        s.setId(UUID.randomUUID());
        s.setDocumentId(documentId);
        s.setRunId(UUID.randomUUID());
        s.setContentSource(source);
        s.setCurrentText(text);
        s.setDeleted(deleted);
        s.setPageNo(3);
        s.setLineFrom(4);
        s.setLineTo(6);
        s.setCreatedAt(OffsetDateTime.now());
        s.setUpdatedAt(OffsetDateTime.now());
        return s;
    }

    private FusedHit hit(String segmentId, boolean lexical, Double cosine) {
        return new FusedHit(segmentId, documentId, cosine, lexical, 0.03);
    }

    @Test
    void excerptComesFromPostgresCurrentTextNotFromTheIndex() {
        ContentSegment ocr = segment("ocr", "Noi dung moi nhat sau khi sua", false);
        postgresAllows(document());
        when(segmentRepository.findAllById(anyIterable())).thenReturn(List.of(ocr));

        List<SemanticResult> results = hydrator.hydrate(List.of(hit(ocr.getId().toString(), true, 0.8)), ctx);

        assertThat(results).hasSize(1);
        SemanticResult r = results.get(0);
        assertThat(r.excerpt()).isEqualTo("Noi dung moi nhat sau khi sua");
        assertThat(r.documentName()).isEqualTo("Quyet-dinh-12.pdf");
        assertThat(r.contentSource()).isEqualTo("ocr");
        assertThat(r.confirmSource()).isEqualTo("auto");
        assertThat(r.position().type()).isEqualTo("page");
        assertThat(r.position().pageNo()).isEqualTo(3);
        assertThat(r.matchedLexical()).isTrue();
        assertThat(r.cosineScore()).isEqualTo(0.8);
    }

    @Test
    void confirmSourceComesFromTheRunNotFromWhetherTheSegmentWasEdited() {
        ContentSegment unedited = segment("ocr", "Dieu 1. Ho so luu tru toi thieu 10 nam", false);
        DigitizationRun run = new DigitizationRun();
        ReflectionTestUtils.setField(run, "id", unedited.getRunId());
        run.setConfirmSource(DigitizationRun.CONFIRM_MANUAL_UNCHANGED);
        postgresAllows(document());
        when(segmentRepository.findAllById(anyIterable())).thenReturn(List.of(unedited));
        when(runRepository.findAllById(anyIterable())).thenReturn(List.of(run));

        List<SemanticResult> results = hydrator.hydrate(List.of(hit(unedited.getId().toString(), false, 0.8)), ctx);

        // AC-2.1: a manually confirmed run never carries the "tự động xác nhận" badge, edited or not.
        assertThat(results.get(0).confirmSource()).isEqualTo("manual");
    }

    @Test
    void aDocumentThatFailsThePostgresAclCheckIsDroppedEvenIfTheIndexSaidItMatched() {
        ContentSegment ocr = segment("ocr", "bi mat cua nguoi khac", false);
        postgresAllows(); // BusinessDataScope returned nothing for this caller
        when(segmentRepository.findAllById(anyIterable())).thenReturn(List.of(ocr));

        assertThat(hydrator.hydrate(List.of(hit(ocr.getId().toString(), false, 0.99)), ctx)).isEmpty();
    }

    @Test
    void sedSoftDeletedAndVanishedSegmentsNeverAppear() {
        ContentSegment sed = segment("sed", "vo tay", false);
        ContentSegment deleted = segment("ocr", "da xoa", true);
        ContentSegment blank = segment("ocr", "   ", false);
        postgresAllows(document());
        when(segmentRepository.findAllById(anyIterable())).thenReturn(List.of(sed, deleted, blank));

        List<SemanticResult> results = hydrator.hydrate(List.of(
                hit(sed.getId().toString(), false, 0.9),
                hit(deleted.getId().toString(), false, 0.9),
                hit(blank.getId().toString(), false, 0.9),
                hit(UUID.randomUUID().toString(), false, 0.9)), ctx);

        assertThat(results).isEmpty();
    }

    @Test
    void summaryHasNoPositionAndTitleAndNoteSegmentsAreResolvedWithoutAContentSegmentRow() {
        ContentSegment summary = segment("summary", "Tom tat noi dung video", false);
        postgresAllows(document());
        when(segmentRepository.findAllById(anyIterable())).thenReturn(List.of(summary));
        DocumentNote older = new DocumentNote();
        older.setBody("ghi chu 1");
        older.setCreatedAt(OffsetDateTime.now().minusDays(1));
        DocumentNote newer = new DocumentNote();
        newer.setBody("ghi chu 2");
        newer.setCreatedAt(OffsetDateTime.now());
        when(noteRepository.findByDocumentIdOrderByCreatedAtDesc(documentId)).thenReturn(new ArrayList<>(List.of(newer, older)));

        List<SemanticResult> results = hydrator.hydrate(List.of(
                hit(summary.getId().toString(), false, 0.7),
                hit(documentId + ":title", true, null),
                hit(documentId + ":note", true, null),
                hit("garbage-id", true, null)), ctx);

        assertThat(results).extracting(SemanticResult::contentSource).containsExactly("summary", "title", "note");
        assertThat(results.get(0).position().type()).isEqualTo("none"); // AC-2.5: no specific position
        assertThat(results.get(1).excerpt()).isEqualTo("Quyet-dinh-12.pdf");
        assertThat(results.get(1).position().type()).isEqualTo("none");
        assertThat(results.get(2).excerpt()).isEqualTo("ghi chu 1\n\nghi chu 2");
        assertThat(results.get(2).confirmSource()).isNull();
    }

    @Test
    void orderOfTheFusedInputIsPreservedAndDigitizationStatusIsAttached() {
        ContentSegment a = segment("ocr", "a", false);
        ContentSegment b = segment("ocr", "b", false);
        postgresAllows(document());
        when(segmentRepository.findAllById(anyIterable())).thenReturn(List.of(a, b));
        DocumentDigitizationStatus status = mock(DocumentDigitizationStatus.class);
        when(status.getDocumentId()).thenReturn(documentId);
        when(status.getOverallStatus()).thenReturn("pending_review");
        when(statusRepository.findAllById(anyIterable())).thenReturn(List.of(status));

        List<SemanticResult> results = hydrator.hydrate(
                List.of(hit(b.getId().toString(), false, 0.9), hit(a.getId().toString(), false, 0.8)), ctx);

        assertThat(results).extracting(SemanticResult::excerpt).containsExactly("b", "a");
        assertThat(results).allMatch(r -> "pending_review".equals(r.documentDigitizationStatus()));
    }

    @Test
    void aSegmentOfAnotherDocumentIsNotAcceptedUnderThisDocument() {
        ContentSegment foreign = segment("ocr", "noi dung tai lieu khac", false);
        foreign.setDocumentId(UUID.randomUUID());
        postgresAllows(document());
        when(segmentRepository.findAllById(anyIterable())).thenReturn(List.of(foreign));

        assertThat(hydrator.hydrate(List.of(hit(foreign.getId().toString(), false, 0.9)), ctx)).isEmpty();
    }
}
