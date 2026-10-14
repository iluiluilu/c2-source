package com.aismartstore.backend.search.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.config.EmbeddingProperties;
import com.aismartstore.backend.config.QueueProperties;
import com.aismartstore.backend.config.SemanticProperties;
import com.aismartstore.backend.digitization.results.ContentSegment;
import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.notes.DocumentNoteRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.HitsMetadata;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

/** SPEC-search-semantic T9 (indexing §5): missing / wrong model_version => republish; complete => nothing. */
class SemanticIndexReconciliationJobTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DigitizationRunRepository runRepository = mock(DigitizationRunRepository.class);
    private final ContentSegmentRepository segmentRepository = mock(ContentSegmentRepository.class);
    private final DocumentNoteRepository noteRepository = mock(DocumentNoteRepository.class);
    private final OpenSearchClient openSearch = mock(OpenSearchClient.class);
    private final EmbedRequestPublisher publisher = mock(EmbedRequestPublisher.class);
    private SemanticIndexReconciliationJob job;

    private final UUID documentId = UUID.randomUUID();
    private ContentSegment ocr;
    private Document document;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        EmbeddingProperties props = new EmbeddingProperties("http://x", 32, 500, 30000, "m", "ver-2", 1024, 512, 64, true, "i");
        job = new SemanticIndexReconciliationJob(
                documentRepository, runRepository, segmentRepository, noteRepository, openSearch, publisher,
                mock(AmqpAdmin.class), new QueueProperties("a", "b", "c", "q.embed"), props,
                new SemanticProperties(true, 8, 500, 600000, 100, 1, 5, 3600000L));

        document = new Document();
        ReflectionTestUtils.setField(document, "id", documentId);
        document.setOriginalName("tai-lieu.pdf");
        Page<Document> page = new PageImpl<>(List.of(document));
        when(documentRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        UUID runId = UUID.randomUUID();
        DigitizationRun run = new DigitizationRun();
        ReflectionTestUtils.setField(run, "id", runId);
        when(runRepository.findByDocumentIdAndReviewState(documentId, DigitizationRun.REVIEW_CONFIRMED)).thenReturn(List.of(run));
        ocr = new ContentSegment();
        ocr.setId(UUID.randomUUID());
        ocr.setContentSource("ocr");
        ocr.setCurrentText("noi dung");
        ContentSegment sed = new ContentSegment();
        sed.setId(UUID.randomUUID());
        sed.setContentSource("sed");
        sed.setCurrentText("vo tay");
        when(segmentRepository.findByRunIdOrderBySegmentIndex(runId)).thenReturn(List.of(ocr, sed));
        when(noteRepository.findByDocumentIdOrderByCreatedAtDesc(documentId)).thenReturn(List.of());
    }

    private void indexHasVectorsFor(String... ids) throws Exception {
        List<Hit<Void>> hits = java.util.Arrays.stream(ids)
                .map(id -> new Hit.Builder<Void>().index("segment-v2").id(id).build())
                .toList();
        SearchResponse<Void> response = mock(SearchResponse.class);
        HitsMetadata<Void> meta = mock(HitsMetadata.class);
        when(response.hits()).thenReturn(meta);
        when(meta.hits()).thenReturn(hits);
        when(openSearch.search(any(SearchRequest.class), eq(Void.class))).thenReturn(response);
    }

    @Test
    void missingVectorsAreRepublishedWithReasonReconcileForExactlyTheMissingIds() throws Exception {
        indexHasVectorsFor(documentId + ":title"); // title ok, the ocr segment lost its vector

        job.reconcileBatch();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        verify(publisher).publish(eq(documentId), ids.capture(), eq("reconcile"), anyLong());
        assertThat(ids.getValue()).containsExactly(ocr.getId().toString()); // sed is never expected
    }

    @Test
    void theIndexQueryRequiresTheEmbeddingFieldAndTheCurrentModelVersion() throws Exception {
        indexHasVectorsFor(documentId + ":title", ocr.getId().toString());

        job.reconcileBatch();

        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(openSearch).search(request.capture(), eq(Void.class));
        org.opensearch.client.json.jackson.JacksonJsonpMapper mapper = new org.opensearch.client.json.jackson.JacksonJsonpMapper();
        java.io.StringWriter out = new java.io.StringWriter();
        try (jakarta.json.stream.JsonGenerator generator = mapper.jsonProvider().createGenerator(out)) {
            mapper.serialize(request.getValue().query(), generator);
        }
        String query = out.toString();
        assertThat(query).contains("exists").contains("embedding").contains("model_version").contains("ver-2");
    }

    @Test
    void whenEverythingHasAVectorNothingIsPublished() throws Exception {
        indexHasVectorsFor(documentId + ":title", ocr.getId().toString());

        job.reconcileBatch();

        verify(publisher, never()).publish(any(), any(), anyString(), anyLong());
    }

    @Test
    void theScheduledRunDoesNothingWhenTheFlagIsOff() {
        SemanticIndexReconciliationJob off = new SemanticIndexReconciliationJob(
                documentRepository, runRepository, segmentRepository, noteRepository, openSearch, publisher,
                mock(AmqpAdmin.class), new QueueProperties("a", "b", "c", "q.embed"),
                new EmbeddingProperties("http://x", 32, 500, 30000, "m", "ver-2", 1024, 512, 64, true, "i"),
                new SemanticProperties(false, 8, 500, 600000, 100, 1, 5, 3600000L));

        off.run();

        org.mockito.Mockito.verifyNoInteractions(documentRepository, openSearch, publisher);
    }

    @Test
    void aDocumentRequestedWithinTheGraceWindowIsSkipped() throws Exception {
        document.setEmbedRequestedAt(java.time.OffsetDateTime.now().minusMinutes(5)); // grace = 1h
        indexHasVectorsFor(); // would be "missing" otherwise

        job.reconcileBatch();

        verify(publisher, never()).publish(any(), any(), anyString(), anyLong());
    }

    @Test
    void aDocumentRequestedLongAgoIsRequestedAgain() throws Exception {
        document.setEmbedRequestedAt(java.time.OffsetDateTime.now().minusHours(2));
        indexHasVectorsFor();

        job.reconcileBatch();

        verify(publisher).publish(eq(documentId), any(), eq("reconcile"), anyLong());
    }
}
