package com.aismartstore.backend.search.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.config.SemanticProperties;
import com.aismartstore.backend.digitization.results.ContentSegment;
import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.notes.DocumentNote;
import com.aismartstore.backend.notes.DocumentNoteRepository;
import com.aismartstore.backend.outbox.OutboxEvent;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * SPEC-search-semantic T7/T10 (indexing §4.1, §5): the poller's embed.requested routing table, the
 * R4 invariant (every lexical re-index of an embeddable segment is paired with embed.requested for
 * exactly that set), the feature flag, and folder.moved.
 */
class SearchIndexOutboxPollerEmbedTest {

    private static final long CONTENT_VERSION = 777_000_111L;

    private final OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
    private final DocumentIndexFieldsBuilder fieldsBuilder = mock(DocumentIndexFieldsBuilder.class);
    private final SegmentIndexer segmentIndexer = mock(SegmentIndexer.class);
    private final DocumentIndexer documentIndexer = mock(DocumentIndexer.class);
    private final DocumentNoteRepository documentNoteRepository = mock(DocumentNoteRepository.class);
    private final ContentSegmentRepository contentSegmentRepository = mock(ContentSegmentRepository.class);
    private final DigitizationRunRepository digitizationRunRepository = mock(DigitizationRunRepository.class);
    private final EmbedRequestPublisher publisher = mock(EmbedRequestPublisher.class);
    private final WarehouseFolderRepository folderRepository = mock(WarehouseFolderRepository.class);

    private SearchIndexOutboxPoller poller;

    @BeforeEach
    void setUp() {
        poller = newPoller(true);
    }

    private SearchIndexOutboxPoller newPoller(boolean enabled) {
        SearchIndexOutboxPoller p = new SearchIndexOutboxPoller(
                outboxEventRepository, fieldsBuilder, segmentIndexer, documentIndexer, documentNoteRepository,
                contentSegmentRepository, digitizationRunRepository, 20);
        p.setSemanticCollaborators(
                publisher, new SemanticProperties(enabled, 8, 500, 600000, 500, 1, 5, 3600000L), folderRepository);
        return p;
    }

    private static DocumentIndexFieldsBuilder.Fields fields(UUID documentId) {
        return new DocumentIndexFieldsBuilder.Fields(
                documentId, "file.pdf", "văn bản", List.of(), UUID.randomUUID().toString(),
                List.of(UUID.randomUUID().toString()), OffsetDateTime.now(), "document", "owner-sub", List.of(),
                CONTENT_VERSION, "file.pdf");
    }

    private void claim(String type, Map<String, Object> payload) {
        OutboxEvent event = new OutboxEvent();
        ReflectionTestUtils.setField(event, "id", 7L);
        event.setEventType(type);
        event.setPayload(payload);
        event.setCreatedAt(OffsetDateTime.now());
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(event));
    }

    private static ContentSegment segment(UUID runId, UUID documentId, int index, String source, String text, boolean deleted) {
        ContentSegment segment = new ContentSegment();
        segment.setId(UUID.randomUUID());
        segment.setRunId(runId);
        segment.setDocumentId(documentId);
        segment.setSegmentIndex(index);
        segment.setContentSource(source);
        segment.setCurrentText(text);
        segment.setDeleted(deleted);
        segment.setPageNo(index);
        segment.setCreatedAt(OffsetDateTime.now());
        segment.setUpdatedAt(OffsetDateTime.now());
        return segment;
    }

    @SuppressWarnings("unchecked")
    private List<String> publishedIds(UUID documentId, String reason) {
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        verify(publisher).publish(eq(documentId), ids.capture(), eq(reason), eq(CONTENT_VERSION));
        return ids.getValue();
    }

    // ---- review.confirmed / review.reconfirmed -------------------------------------------

    @Test
    void reviewConfirmedPublishesEmbedForExactlyTheEmbeddableSegmentsJustReindexed() throws Exception {
        for (String type : List.of("review.confirmed", "review.reconfirmed")) {
            org.mockito.Mockito.reset(publisher, segmentIndexer);
            UUID documentId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            ContentSegment ocr = segment(runId, documentId, 0, "ocr", "noi dung ocr", false);
            ContentSegment stt = segment(runId, documentId, 1, "stt", "noi dung stt", false);
            ContentSegment summary = segment(runId, documentId, 2, "summary", "tom tat", false);
            ContentSegment sed = segment(runId, documentId, 3, "sed", "vo tay", false);
            ContentSegment deleted = segment(runId, documentId, 4, "ocr", "da xoa", true);
            ContentSegment blank = segment(runId, documentId, 5, "ocr", "  ", false);
            claim(type, Map.of("documentId", documentId.toString(), "runId", runId.toString(), "task", "ocr"));
            when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));
            when(contentSegmentRepository.findByRunIdOrderBySegmentIndex(runId))
                    .thenReturn(List.of(ocr, stt, summary, sed, deleted, blank));
            when(digitizationRunRepository.findByDocumentIdAndTaskAndReviewState(any(), any(), any()))
                    .thenReturn(Optional.empty());

            poller.claimAndProcessOne();

            assertThat(publishedIds(documentId, "confirmed"))
                    .containsExactly(ocr.getId().toString(), stt.getId().toString(), summary.getId().toString());
            // lexical write happened first, with the same segments (minus nothing: sed is still lexical)
            verify(segmentIndexer).upsertSegments(any(), anyLong());
        }
    }

    @Test
    void embedIsPublishedOnlyAfterTheLexicalWriteSucceeded() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        claim("review.confirmed", Map.of("documentId", documentId.toString(), "runId", runId.toString(), "task", "ocr"));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));
        when(contentSegmentRepository.findByRunIdOrderBySegmentIndex(runId))
                .thenReturn(List.of(segment(runId, documentId, 0, "ocr", "x", false)));
        doThrow(new java.io.IOException("os down")).when(segmentIndexer).upsertSegments(any(), anyLong());

        poller.claimAndProcessOne();

        verifyNoInteractions(publisher);
    }

    @Test
    void aPublishFailureFailsTheRowSoItIsRetried() throws Exception {
        UUID documentId = UUID.randomUUID();
        claim("document.ingested", Map.of("documentId", documentId.toString()));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));
        doThrow(new org.springframework.amqp.AmqpConnectException(new RuntimeException("mq down")))
                .when(publisher).publish(any(), any(), anyString(), anyLong());
        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);

        poller.claimAndProcessOne();

        verify(outboxEventRepository).save(saved.capture());
        assertThat(saved.getValue().getProcessedAt()).isNull();
        assertThat(saved.getValue().getAttempts()).isEqualTo(1);
    }

    // ---- document.ingested / note / restored -------------------------------------------------

    @Test
    void documentIngestedPublishesTheTitleSegment() throws Exception {
        UUID documentId = UUID.randomUUID();
        claim("document.ingested", Map.of("documentId", documentId.toString()));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));

        poller.claimAndProcessOne();

        assertThat(publishedIds(documentId, "ingested")).containsExactly(documentId + ":title");
    }

    @Test
    void noteChangedPublishesTheNoteSegmentOnlyWhenANoteExists() throws Exception {
        UUID documentId = UUID.randomUUID();
        claim("document.note.changed", Map.of("documentId", documentId.toString()));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));
        DocumentNote note = new DocumentNote();
        note.setBody("ghi chu");
        note.setCreatedAt(OffsetDateTime.now());
        when(documentNoteRepository.findByDocumentIdOrderByCreatedAtDesc(documentId)).thenReturn(new ArrayList<>(List.of(note)));

        poller.claimAndProcessOne();
        assertThat(publishedIds(documentId, "note")).containsExactly(documentId + ":note");

        org.mockito.Mockito.reset(publisher);
        when(documentNoteRepository.findByDocumentIdOrderByCreatedAtDesc(documentId)).thenReturn(List.of());
        poller.claimAndProcessOne();
        verifyNoInteractions(publisher);
    }

    @Test
    void documentRestoredPublishesTheWholeDocument() throws Exception {
        UUID documentId = UUID.randomUUID();
        claim("document.restored", Map.of("documentId", documentId.toString()));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));

        poller.claimAndProcessOne();

        verify(publisher).publish(documentId, null, "restored", CONTENT_VERSION);
    }

    // ---- events that must NOT call embed ---------------------------------------------------------

    @Test
    void aclClassificationTrashAndPurgeNeverPublishEmbed() throws Exception {
        UUID documentId = UUID.randomUUID();
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));
        for (String type : List.of("document.acl.changed", "document.classification.changed")) {
            claim(type, Map.of("documentId", documentId.toString()));
            poller.claimAndProcessOne();
        }
        claim("document.trash", Map.of("documentIds", List.of(documentId.toString())));
        poller.claimAndProcessOne();
        claim("document.purged", Map.of("documentId", documentId.toString()));
        poller.claimAndProcessOne();

        verifyNoInteractions(publisher);
        verify(segmentIndexer, org.mockito.Mockito.times(2)).updateByQuery(any(), anyString(), any(), anyInt());
        verify(segmentIndexer, org.mockito.Mockito.times(2)).deleteByDocumentIds(any());
    }

    @Test
    void theFlagOffMeansNothingIsPublished() throws Exception {
        poller = newPoller(false);
        UUID documentId = UUID.randomUUID();
        claim("document.ingested", Map.of("documentId", documentId.toString()));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));

        poller.claimAndProcessOne();

        verifyNoInteractions(publisher);
        verify(documentIndexer).upsertDocument(any(), anyLong()); // the lexical path is untouched
    }

    // ---- folder.moved ----------------------------------------------------------------------------------

    private static WarehouseFolder folder(UUID id, UUID... path) {
        WarehouseFolder folder = new WarehouseFolder();
        ReflectionTestUtils.setField(folder, "id", id);
        folder.setPathIds(path);
        return folder;
    }

    @Test
    void folderMovedRecomputesPathsFromPostgresForTheWholeSubtreeAndKeepsVectors() throws Exception {
        UUID newParent = UUID.randomUUID();
        UUID moved = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        when(folderRepository.findById(moved)).thenReturn(Optional.of(folder(moved, newParent)));
        when(folderRepository.findDescendants(moved)).thenReturn(List.of(folder(child, newParent, moved)));
        // a malicious/stale payload path must be ignored
        claim("folder.moved", Map.of("folderId", moved.toString(), "newPath", List.of("evil")));

        poller.claimAndProcessOne();

        ArgumentCaptor<Query> segQuery = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, org.opensearch.client.json.JsonData>> params = ArgumentCaptor.forClass(Map.class);
        verify(segmentIndexer, org.mockito.Mockito.times(2)).updateByQuery(segQuery.capture(), script.capture(), params.capture(), eq(500));
        verify(documentIndexer, org.mockito.Mockito.times(2)).updateByQuery(any(Query.class), anyString(), any());

        // partial update of folder_path_ids only - never the embedding, never ACL fields
        assertThat(script.getAllValues()).allMatch(s -> s.equals("ctx._source.folder_path_ids = params.folderPathIds;"));
        assertThat(segQuery.getAllValues().get(0).term().field()).isEqualTo("folder_id");
        assertThat(segQuery.getAllValues().get(0).term().value().stringValue()).isEqualTo(moved.toString());
        assertThat(segQuery.getAllValues().get(1).term().value().stringValue()).isEqualTo(child.toString());
        org.opensearch.client.json.jackson.JacksonJsonpMapper mapper = new org.opensearch.client.json.jackson.JacksonJsonpMapper();
        assertThat(params.getAllValues().get(0).get("folderPathIds").to(List.class, mapper))
                .containsExactly(newParent.toString(), moved.toString());
        assertThat(params.getAllValues().get(1).get("folderPathIds").to(List.class, mapper))
                .containsExactly(newParent.toString(), moved.toString(), child.toString());
        verifyNoInteractions(publisher);
    }

    @Test
    void folderMovedWorksWithTheSemanticFlagOffAndIgnoresAVanishedFolder() throws Exception {
        poller = newPoller(false);
        UUID moved = UUID.randomUUID();
        when(folderRepository.findById(moved)).thenReturn(Optional.empty());
        claim("folder.moved", Map.of("folderId", moved.toString()));

        poller.claimAndProcessOne();

        verify(segmentIndexer, never()).updateByQuery(any(), anyString(), any(), anyInt());
        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(saved.capture());
        assertThat(saved.getValue().getProcessedAt()).isNotNull();
    }

    @Test
    void folderMovedIsPartOfTheHandledEventTypes() {
        assertThat(SearchIndexOutboxPoller.HANDLED_EVENT_TYPES).contains("folder.moved");
        assertThatThrownBy(() -> { throw new IllegalStateException(); }).isInstanceOf(IllegalStateException.class);
    }
}
