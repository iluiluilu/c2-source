package com.aismartstore.backend.search.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.digitization.results.ContentSegment;
import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.notes.DocumentNote;
import com.aismartstore.backend.notes.DocumentNoteRepository;
import com.aismartstore.backend.outbox.OutboxEvent;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/**
 * search-fulltext-04-dong-bo-chi-muc.md §4.2/§5 - {@link
 * SearchIndexOutboxPoller}'s per-row transaction/dispatch/retry contract,
 * with every OpenSearch/Postgres dependency mocked (no Testcontainers -
 * this exercises the poller's own logic, not a real index).
 */
class SearchIndexOutboxPollerTest {

    private final OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
    private final DocumentIndexFieldsBuilder fieldsBuilder = mock(DocumentIndexFieldsBuilder.class);
    private final SegmentIndexer segmentIndexer = mock(SegmentIndexer.class);
    private final DocumentIndexer documentIndexer = mock(DocumentIndexer.class);
    private final DocumentNoteRepository documentNoteRepository = mock(DocumentNoteRepository.class);
    private final ContentSegmentRepository contentSegmentRepository = mock(ContentSegmentRepository.class);
    private final DigitizationRunRepository digitizationRunRepository = mock(DigitizationRunRepository.class);

    private static final int MAX_ATTEMPTS = 20;

    private SearchIndexOutboxPoller poller;

    @BeforeEach
    void setUp() {
        poller = new SearchIndexOutboxPoller(
                outboxEventRepository, fieldsBuilder, segmentIndexer, documentIndexer, documentNoteRepository,
                contentSegmentRepository, digitizationRunRepository, MAX_ATTEMPTS);
    }

    /**
     * bugs/bug-002-search-index-version-conflict.md mục 5.2 - {@code
     * CONTENT_VERSION} is a fixed, arbitrary constant deliberately NOT equal
     * to any {@code event(id, ...)} used below, so every assertion against it
     * proves the version passed to the indexer comes from {@code
     * Fields.contentVersion()} (computed live from Postgres by {@code
     * DocumentIndexFieldsBuilder}), never from {@code event.getId()} (queue
     * position - the unsafe source the bug fixed).
     */
    private static final long CONTENT_VERSION = 777_000_111L;

    private static DocumentIndexFieldsBuilder.Fields fields(UUID documentId) {
        return new DocumentIndexFieldsBuilder.Fields(
                documentId, "file.pdf", "văn bản", List.of("chu-de"), UUID.randomUUID().toString(),
                List.of(UUID.randomUUID().toString()), OffsetDateTime.now(), "document", "owner-sub", List.of(),
                CONTENT_VERSION);
    }

    private static OutboxEvent event(long id, String eventType, Map<String, Object> payload) {
        OutboxEvent event = new OutboxEvent();
        try {
            java.lang.reflect.Field idField = OutboxEvent.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(event, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setCreatedAt(OffsetDateTime.now());
        return event;
    }

    // ---- claimAndProcessOne: empty queue -----------------------------------

    @Test
    void claimAndProcessOneReturnsFalseWhenTheQueueIsEmpty() {
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.empty());

        assertThat(poller.claimAndProcessOne()).isFalse();
        verify(outboxEventRepository, never()).save(any());
    }

    // ---- document.ingested: upserts document + title segment --------------

    @Test
    void documentIngestedUpsertsDocumentAndATitleSegment() throws Exception {
        UUID documentId = UUID.randomUUID();
        OutboxEvent outboxEvent = event(10, "document.ingested", Map.of("documentId", documentId.toString()));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));

        boolean claimed = poller.claimAndProcessOne();

        assertThat(claimed).isTrue();
        verify(documentIndexer).upsertDocument(any(), eqLong(CONTENT_VERSION));
        ArgumentCaptor<List<Map<String, Object>>> segmentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(segmentIndexer).upsertSegments(segmentsCaptor.capture(), eqLong(CONTENT_VERSION));
        assertThat(segmentsCaptor.getValue()).hasSize(1);
        assertThat(segmentsCaptor.getValue().get(0).get("content_source")).isEqualTo("title");
        assertThat(segmentsCaptor.getValue().get(0).get("text")).isEqualTo("file.pdf");
        assertThat(segmentsCaptor.getValue().get(0).get("index_version")).isEqualTo(CONTENT_VERSION);

        ArgumentCaptor<Map<String, Object>> documentCaptor = ArgumentCaptor.forClass(Map.class);
        verify(documentIndexer).upsertDocument(documentCaptor.capture(), eqLong(CONTENT_VERSION));
        assertThat(documentCaptor.getValue().get("index_version")).isEqualTo(CONTENT_VERSION);

        ArgumentCaptor<OutboxEvent> savedCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getProcessedAt()).isNotNull();
    }

    /**
     * bugs/bug-002-search-index-version-conflict.md - the regression test for
     * the fix itself: uses an {@code event.getId()} (5) that is deliberately
     * SMALLER than the {@code Fields.contentVersion()} (777_000_111, via
     * {@link #fields}) to prove the version handed to OpenSearch tracks the
     * document's actual data freshness (re-read live by {@code
     * DocumentIndexFieldsBuilder} on every call), never the outbox queue
     * position - the unsafe substitution that caused the bug (a document
     * could get PARKED forever on a genuine, non-benign version conflict
     * whenever queue-processing order diverged from creation order).
     */
    @Test
    void versionPassedToOpenSearchComesFromContentVersionNeverFromTheOutboxEventId() throws Exception {
        UUID documentId = UUID.randomUUID();
        OutboxEvent outboxEvent = event(5, "document.ingested", Map.of("documentId", documentId.toString()));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));

        poller.claimAndProcessOne();

        verify(documentIndexer).upsertDocument(any(), eqLong(CONTENT_VERSION));
        verify(documentIndexer, never()).upsertDocument(any(), eqLong(5));
    }

    @Test
    void documentIngestedIsANoOpWhenTheDocumentIsAlreadyGone() throws Exception {
        UUID documentId = UUID.randomUUID();
        OutboxEvent outboxEvent = event(11, "document.ingested", Map.of("documentId", documentId.toString()));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.empty());

        poller.claimAndProcessOne();

        verify(documentIndexer, never()).upsertDocument(any(), anyLong());
        verify(segmentIndexer, never()).upsertSegments(any(), anyLong());
        assertThat(outboxEvent.getProcessedAt()).isNotNull(); // still marked processed - nothing left to reflect
    }

    // ---- document.note.changed: merges every note, oldest first ------------

    @Test
    void noteChangedMergesEveryNoteOldestFirstIntoOneSegment() throws Exception {
        UUID documentId = UUID.randomUUID();
        OutboxEvent outboxEvent = event(20, "document.note.changed", Map.of("documentId", documentId.toString()));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));

        DocumentNote newer = note(documentId, "ghi chú mới", OffsetDateTime.now());
        DocumentNote older = note(documentId, "ghi chú cũ", OffsetDateTime.now().minusHours(1));
        // repository method is *Desc* - the poller itself must reverse it to oldest-first.
        when(documentNoteRepository.findByDocumentIdOrderByCreatedAtDesc(documentId)).thenReturn(List.of(newer, older));

        poller.claimAndProcessOne();

        ArgumentCaptor<List<Map<String, Object>>> segmentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(segmentIndexer).upsertSegments(segmentsCaptor.capture(), eqLong(CONTENT_VERSION));
        Map<String, Object> segment = segmentsCaptor.getValue().get(0);
        assertThat(segment.get("content_source")).isEqualTo("note");
        assertThat(segment.get("text")).isEqualTo("ghi chú cũ\n\nghi chú mới");
        assertThat(segment.get("index_version")).isEqualTo(CONTENT_VERSION);
    }

    @Test
    void noteChangedIsANoOpWhenThereAreNoNotesLeft() throws Exception {
        UUID documentId = UUID.randomUUID();
        OutboxEvent outboxEvent = event(21, "document.note.changed", Map.of("documentId", documentId.toString()));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));
        when(documentNoteRepository.findByDocumentIdOrderByCreatedAtDesc(documentId)).thenReturn(List.of());

        poller.claimAndProcessOne();

        verify(segmentIndexer, never()).upsertSegments(any(), anyLong());
    }

    // ---- document.classification.changed / document.acl.changed: update_by_query on both indices ----

    @Test
    void classificationChangedUpdatesBothIndicesByQuery() throws Exception {
        UUID documentId = UUID.randomUUID();
        OutboxEvent outboxEvent = event(30, "document.classification.changed", Map.of("documentId", documentId.toString()));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));

        poller.claimAndProcessOne();

        verify(segmentIndexer).updateByQuery(any(), any(), any(), anyInt());
        verify(documentIndexer).updateByQuery(org.mockito.ArgumentMatchers.eq(documentId), any(), any());
    }

    @Test
    void aclChangedUpdatesBothIndicesByQuery() throws Exception {
        UUID documentId = UUID.randomUUID();
        OutboxEvent outboxEvent = event(31, "document.acl.changed", Map.of(
                "documentId", documentId.toString(), "groupId", UUID.randomUUID().toString(), "action", "shared"));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));

        poller.claimAndProcessOne();

        verify(segmentIndexer).updateByQuery(any(), any(), any(), anyInt());
        verify(documentIndexer).updateByQuery(org.mockito.ArgumentMatchers.eq(documentId), any(), any());
    }

    /**
     * bugs/bug-003-versioned-set-script-invalid-noop.md - the skip branch of
     * {@code versionedSetScript()} used to write {@code ctx.op = 'none'}, an
     * OpenSearch/Painless {@code illegal_argument_exception} (only
     * {@code noop}/{@code index}/{@code delete} are valid). Neither existing
     * test above caught this because they only asserted the script param
     * with {@code any()} - never inspected its text.
     */
    @Test
    void classificationChangedAndAclChangedScriptsUseTheValidNoopSentinelOnTheSkipBranch() throws Exception {
        UUID documentId = UUID.randomUUID();
        OutboxEvent classificationEvent =
                event(32, "document.classification.changed", Map.of("documentId", documentId.toString()));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(classificationEvent));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));

        poller.claimAndProcessOne();

        ArgumentCaptor<String> classificationScript = ArgumentCaptor.forClass(String.class);
        verify(documentIndexer).updateByQuery(org.mockito.ArgumentMatchers.eq(documentId), classificationScript.capture(), any());
        assertThat(classificationScript.getValue()).contains("ctx.op = 'noop'").doesNotContain("ctx.op = 'none'");

        UUID documentId2 = UUID.randomUUID();
        OutboxEvent aclEvent = event(33, "document.acl.changed", Map.of("documentId", documentId2.toString()));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(aclEvent));
        when(fieldsBuilder.build(documentId2)).thenReturn(Optional.of(fields(documentId2)));

        poller.claimAndProcessOne();

        ArgumentCaptor<String> aclScript = ArgumentCaptor.forClass(String.class);
        verify(documentIndexer).updateByQuery(org.mockito.ArgumentMatchers.eq(documentId2), aclScript.capture(), any());
        assertThat(aclScript.getValue()).contains("ctx.op = 'noop'").doesNotContain("ctx.op = 'none'");
    }

    // ---- review.confirmed: upserts non-deleted segments, drops superseded run's segments ----

    @Test
    void reviewConfirmedUpsertsNonDeletedSegmentsAndDeletesTheSupersededRun() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID supersededRunId = UUID.randomUUID();
        OutboxEvent outboxEvent = event(40, "review.confirmed", Map.of(
                "documentId", documentId.toString(), "runId", runId.toString(), "task", "ocr",
                "confirmSource", "manual_unchanged", "confirmedAt", OffsetDateTime.now().toString(), "segmentCount", 2));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));

        ContentSegment kept = contentSegment(runId, documentId, 0, false);
        ContentSegment deleted = contentSegment(runId, documentId, 1, true);
        when(contentSegmentRepository.findByRunIdOrderBySegmentIndex(runId)).thenReturn(List.of(kept, deleted));

        DigitizationRun superseded = new DigitizationRun();
        superseded.setId(supersededRunId);
        when(digitizationRunRepository.findByDocumentIdAndTaskAndReviewState(documentId, "ocr", DigitizationRun.REVIEW_SUPERSEDED))
                .thenReturn(Optional.of(superseded));

        poller.claimAndProcessOne();

        ArgumentCaptor<List<Map<String, Object>>> segmentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(segmentIndexer).upsertSegments(segmentsCaptor.capture(), eqLong(CONTENT_VERSION));
        assertThat(segmentsCaptor.getValue()).hasSize(1); // only the non-deleted one
        assertThat(segmentsCaptor.getValue().get(0).get("segment_id")).isEqualTo(kept.getId().toString());
        // Regression: contentSegmentFields()/putCommonFields() used to never set index_version at all for
        // OCR/STT/summary segments (only the note/title/document paths set it by hand) - stg showed exactly
        // this split on real data (title segments had index_version, ocr/stt segments didn't).
        assertThat(segmentsCaptor.getValue().get(0).get("index_version")).isEqualTo(CONTENT_VERSION);

        ArgumentCaptor<Map<String, Object>> documentCaptor = ArgumentCaptor.forClass(Map.class);
        verify(segmentIndexer).deleteByRunId(supersededRunId);
        verify(documentIndexer).upsertDocument(documentCaptor.capture(), eqLong(CONTENT_VERSION));
        assertThat(documentCaptor.getValue().get("index_version")).isEqualTo(CONTENT_VERSION);
    }

    /**
     * search-fulltext-01-tim-kiem-co-ban.md §2.1 - index_version is the single
     * version contract for every write, regardless of content_source; before
     * the fix only the OCR branch happened to be exercised by the other test
     * above, so this pins down STT and the "no finer position" (summary)
     * branch too, both of which go through the exact same putCommonFields()
     * call and were equally missing the field.
     */
    @Test
    void reviewConfirmedSetsIndexVersionOnEveryContentSourceNotJustOcr() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        OutboxEvent outboxEvent = event(42, "review.confirmed", Map.of(
                "documentId", documentId.toString(), "runId", runId.toString(), "task", "stt",
                "confirmSource", "auto", "confirmedAt", OffsetDateTime.now().toString(), "segmentCount", 2));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));

        ContentSegment stt = contentSegment(runId, documentId, 0, false, ContentSegment.SOURCE_STT);
        ContentSegment summary = contentSegment(runId, documentId, 1, false, "summary");
        when(contentSegmentRepository.findByRunIdOrderBySegmentIndex(runId)).thenReturn(List.of(stt, summary));
        when(digitizationRunRepository.findByDocumentIdAndTaskAndReviewState(documentId, "stt", DigitizationRun.REVIEW_SUPERSEDED))
                .thenReturn(Optional.empty());

        poller.claimAndProcessOne();

        ArgumentCaptor<List<Map<String, Object>>> segmentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(segmentIndexer).upsertSegments(segmentsCaptor.capture(), eqLong(CONTENT_VERSION));
        assertThat(segmentsCaptor.getValue()).hasSize(2)
                .allSatisfy(m -> assertThat(m.get("index_version")).isEqualTo(CONTENT_VERSION));
    }

    @Test
    void reviewConfirmedDoesNotDeleteAnythingWhenThereIsNoSupersededRun() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        OutboxEvent outboxEvent = event(41, "review.reconfirmed", Map.of(
                "documentId", documentId.toString(), "runId", runId.toString(), "task", "stt",
                "confirmSource", "auto", "confirmedAt", OffsetDateTime.now().toString(), "segmentCount", 1));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));
        when(contentSegmentRepository.findByRunIdOrderBySegmentIndex(runId)).thenReturn(List.of());
        when(digitizationRunRepository.findByDocumentIdAndTaskAndReviewState(documentId, "stt", DigitizationRun.REVIEW_SUPERSEDED))
                .thenReturn(Optional.empty());

        poller.claimAndProcessOne();

        verify(segmentIndexer, never()).deleteByRunId(any());
    }

    // ---- document.trash: batch delete via terms query, never per-id loop ----

    @Test
    void documentTrashedWithOneIdDeletesFromBothIndicesWithASingleElementList() throws Exception {
        UUID documentId = UUID.randomUUID();
        OutboxEvent outboxEvent = event(70, "document.trash", Map.of("documentIds", List.of(documentId.toString())));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));

        poller.claimAndProcessOne();

        verify(segmentIndexer, times(1)).deleteByDocumentIds(List.of(documentId));
        verify(documentIndexer, times(1)).deleteByIds(List.of(documentId));
        assertThat(outboxEvent.getProcessedAt()).isNotNull();
    }

    @Test
    void documentTrashedWithManyIdsDeletesBothIndicesExactlyOncePerMethodWithTheWholeBatch() throws Exception {
        List<UUID> documentIds = java.util.stream.IntStream.range(0, 500)
                .mapToObj(i -> UUID.randomUUID())
                .toList();
        OutboxEvent outboxEvent = event(
                71, "document.trash",
                Map.of("documentIds", documentIds.stream().map(UUID::toString).toList()));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));

        poller.claimAndProcessOne();

        verify(segmentIndexer, times(1)).deleteByDocumentIds(documentIds);
        verify(documentIndexer, times(1)).deleteByIds(documentIds);
    }

    /** A document that was never indexed (e.g. trashed right after a failed ingest) - a no-op, not a failure. */
    @Test
    void documentTrashedOfADocumentNeverIndexedIsANoOp() throws Exception {
        UUID documentId = UUID.randomUUID();
        OutboxEvent outboxEvent = event(72, "document.trash", Map.of("documentIds", List.of(documentId.toString())));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));
        // segmentIndexer/documentIndexer are mocks - deleteByDocumentIds/deleteByIds default to a no-op void call,
        // exactly mirroring OpenSearch's own deleteByQuery not throwing on a `terms` filter that matches nothing.

        boolean claimed = poller.claimAndProcessOne();

        assertThat(claimed).isTrue();
        assertThat(outboxEvent.getProcessedAt()).isNotNull();
        assertThat(outboxEvent.getAttempts()).isZero();
    }

    // ---- document.restored: rebuilds document + every segment from Postgres, like a fresh ingest+confirm ----

    @Test
    void documentRestoredUpsertsDocumentTitleNoteAndEveryConfirmedSegmentAcrossAllTasks() throws Exception {
        UUID documentId = UUID.randomUUID();
        OutboxEvent outboxEvent = event(80, "document.restored", Map.of("documentId", documentId.toString()));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));

        DocumentNote note = note(documentId, "ghi chú", OffsetDateTime.now());
        when(documentNoteRepository.findByDocumentIdOrderByCreatedAtDesc(documentId)).thenReturn(List.of(note));

        DigitizationRun ocrRun = new DigitizationRun();
        UUID ocrRunId = UUID.randomUUID();
        ocrRun.setId(ocrRunId);
        DigitizationRun sttRun = new DigitizationRun();
        UUID sttRunId = UUID.randomUUID();
        sttRun.setId(sttRunId);
        when(digitizationRunRepository.findByDocumentIdAndReviewState(documentId, DigitizationRun.REVIEW_CONFIRMED))
                .thenReturn(List.of(ocrRun, sttRun));
        ContentSegment ocrKept = contentSegment(ocrRunId, documentId, 0, false);
        ContentSegment ocrDeleted = contentSegment(ocrRunId, documentId, 1, true);
        ContentSegment sttKept = contentSegment(sttRunId, documentId, 0, false);
        when(contentSegmentRepository.findByRunIdOrderBySegmentIndex(ocrRunId)).thenReturn(List.of(ocrKept, ocrDeleted));
        when(contentSegmentRepository.findByRunIdOrderBySegmentIndex(sttRunId)).thenReturn(List.of(sttKept));

        poller.claimAndProcessOne();

        verify(documentIndexer).upsertDocument(any(), eqLong(CONTENT_VERSION));

        ArgumentCaptor<List<Map<String, Object>>> segmentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(segmentIndexer, times(3)).upsertSegments(segmentsCaptor.capture(), eqLong(CONTENT_VERSION));
        List<List<Map<String, Object>>> calls = segmentsCaptor.getAllValues();
        // Order per handleDocumentRestored: title, then every confirmed segment across all tasks, then note.
        assertThat(calls.get(0)).hasSize(1);
        assertThat(calls.get(0).get(0).get("content_source")).isEqualTo("title");
        assertThat(calls.get(1)).extracting(m -> m.get("segment_id"))
                .containsExactlyInAnyOrder(ocrKept.getId().toString(), sttKept.getId().toString());
        assertThat(calls.get(1)).allSatisfy(m -> assertThat(m.get("index_version")).isEqualTo(CONTENT_VERSION));
        assertThat(calls.get(2)).hasSize(1);
        assertThat(calls.get(2).get(0).get("content_source")).isEqualTo("note");
        assertThat(calls.get(2).get(0).get("text")).isEqualTo("ghi chú");
        assertThat(calls.get(2).get(0).get("index_version")).isEqualTo(CONTENT_VERSION);
    }

    @Test
    void documentRestoredIsANoOpWhenTheDocumentIsGoneAgain() throws Exception {
        UUID documentId = UUID.randomUUID();
        OutboxEvent outboxEvent = event(81, "document.restored", Map.of("documentId", documentId.toString()));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.empty()); // purged again right after restore (race hiếm)

        poller.claimAndProcessOne();

        verify(documentIndexer, never()).upsertDocument(any(), anyLong());
        verify(segmentIndexer, never()).upsertSegments(any(), anyLong());
        assertThat(outboxEvent.getProcessedAt()).isNotNull();
    }

    // ---- document.purged: same delete path as document.trash, idempotent after a prior trash ----

    @Test
    void documentPurgedDeletesFromBothIndicesTheSameWayAsDocumentTrashed() throws Exception {
        UUID documentId = UUID.randomUUID();
        OutboxEvent outboxEvent = event(90, "document.purged", Map.of("documentId", documentId.toString()));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));

        poller.claimAndProcessOne();

        verify(segmentIndexer, times(1)).deleteByDocumentIds(List.of(documentId));
        verify(documentIndexer, times(1)).deleteByIds(List.of(documentId));
    }

    /** document.purged arriving after document.trash already ran for the same document - still a no-op, not a failure. */
    @Test
    void documentPurgedAfterDocumentTrashedAlreadyRanIsStillANoOp() throws Exception {
        UUID documentId = UUID.randomUUID();
        OutboxEvent outboxEvent = event(91, "document.purged", Map.of("documentId", documentId.toString()));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));
        // No stubbed exception on segmentIndexer/documentIndexer - mirrors deleteByQuery's `deleted=0`,
        // no-exception contract when nothing in the index matches the terms filter any more.

        boolean claimed = poller.claimAndProcessOne();

        assertThat(claimed).isTrue();
        assertThat(outboxEvent.getProcessedAt()).isNotNull();
        assertThat(outboxEvent.getAttempts()).isZero();
    }

    // ---- HANDLED_EVENT_TYPES: every event type this poller must claim, spelled out so an addition is never missed ----

    @Test
    void handledEventTypesListsAllNineEventTypesThisPollerReactsTo() {
        assertThat(SearchIndexOutboxPoller.HANDLED_EVENT_TYPES).containsExactlyInAnyOrder(
                "document.classification.changed",
                "document.note.changed",
                "document.ingested",
                "review.confirmed",
                "review.reconfirmed",
                "document.acl.changed",
                "document.trash",
                "document.restored",
                "document.purged");
    }

    // ---- retry contract: a failure increments attempts and never marks processed_at ----

    @Test
    void aFailingEventIsNotMarkedProcessedAndIncrementsAttempts() throws Exception {
        UUID documentId = UUID.randomUUID();
        OutboxEvent outboxEvent = event(50, "document.ingested", Map.of("documentId", documentId.toString()));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));
        org.mockito.Mockito.doThrow(new java.io.IOException("opensearch down"))
                .when(documentIndexer).upsertDocument(any(), anyLong());

        boolean claimed = poller.claimAndProcessOne();

        assertThat(claimed).isTrue();
        ArgumentCaptor<OutboxEvent> savedCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getProcessedAt()).isNull();
        assertThat(savedCaptor.getValue().getAttempts()).isEqualTo(1);
        assertThat(savedCaptor.getValue().getLastError()).isNotBlank();
    }

    // ---- poll(): loops claimAndProcessOne via the self-proxy until the queue is empty ----

    @Test
    void pollStopsAfterTheQueueGoesEmpty() throws Exception {
        UUID documentId = UUID.randomUUID();
        OutboxEvent first = event(60, "document.ingested", Map.of("documentId", documentId.toString()));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt()))
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.empty());

        poller.poll();

        verify(outboxEventRepository, times(2)).claimNextForIndexing(any(), anyInt());
        verify(outboxEventRepository, times(1)).save(any());
    }

    private static long eqLong(long value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    private static DocumentNote note(UUID documentId, String body, OffsetDateTime createdAt) {
        DocumentNote note = new DocumentNote();
        note.setDocumentId(documentId);
        note.setBody(body);
        note.setCreatedBySub("user-sub");
        note.setCreatedByUsername("user");
        note.setCreatedAt(createdAt);
        note.setUpdatedAt(createdAt);
        return note;
    }

    private static ContentSegment contentSegment(UUID runId, UUID documentId, int index, boolean deleted) {
        return contentSegment(runId, documentId, index, deleted, ContentSegment.SOURCE_OCR);
    }

    private static ContentSegment contentSegment(
            UUID runId, UUID documentId, int index, boolean deleted, String contentSource) {
        ContentSegment segment = new ContentSegment();
        segment.setId(UUID.randomUUID());
        segment.setRunId(runId);
        segment.setDocumentId(documentId);
        segment.setSegmentIndex(index);
        segment.setContentSource(contentSource);
        segment.setCurrentText("noi dung " + index);
        segment.setDeleted(deleted);
        segment.setEdited(false);
        segment.setPageNo(index);
        segment.setCreatedAt(OffsetDateTime.now());
        segment.setUpdatedAt(OffsetDateTime.now());
        return segment;
    }

    // ---- §4.4.1: a poisoned row is parked, and the queue keeps moving -------

    /**
     * The claim-query half of §4.4.1 ({@code attempts < :maxAttempts}) is SQL
     * and belongs to {@code SearchIndexOutboxPollerParkingIT}; what is
     * verifiable here is the poller's half of the contract: it keeps counting
     * attempts up to the threshold, and announces the parking exactly once
     * rather than on every retry forever (~414 ERROR lines/minute on stg
     * before this change).
     */
    @Test
    void parksARowByAnnouncingItExactlyOnceWhenAttemptsReachTheThreshold() throws Exception {
        UUID documentId = UUID.randomUUID();
        OutboxEvent outboxEvent = event(99, "document.ingested", Map.of("documentId", documentId.toString()));
        outboxEvent.setAttempts(MAX_ATTEMPTS - 1); // the next failure is the one that parks it
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));
        doThrow(new java.io.IOException("OpenSearch is unreachable"))
                .when(documentIndexer).upsertDocument(any(), anyLong());

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            assertThat(poller.claimAndProcessOne()).isTrue();
        } finally {
            detachAppender(appender);
        }

        assertThat(outboxEvent.getAttempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(outboxEvent.getProcessedAt()).isNull();      // parked, never "done"
        assertThat(outboxEvent.getLastError()).contains("unreachable"); // evidence kept
        assertThat(appender.list)
                .filteredOn(e -> e.getLevel() == Level.ERROR)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("PARKED"));
    }

    /**
     * The claim query passes the configured threshold through, so a
     * redeployment that raises/lowers {@code app.search-index.max-attempts}
     * actually changes what gets parked.
     */
    @Test
    void passesTheConfiguredMaxAttemptsToTheClaimQuery() {
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.empty());

        poller.claimAndProcessOne();

        verify(outboxEventRepository).claimNextForIndexing(any(), org.mockito.ArgumentMatchers.eq(MAX_ATTEMPTS));
    }

    /** Regression for §4.4.1: a row that merely fails a few times must not be parked or dropped. */
    @Test
    void doesNotParkARowThatIsStillWellUnderTheThreshold() throws Exception {
        UUID documentId = UUID.randomUUID();
        OutboxEvent outboxEvent = event(100, "document.ingested", Map.of("documentId", documentId.toString()));
        outboxEvent.setAttempts(2);
        when(outboxEventRepository.claimNextForIndexing(any(), anyInt())).thenReturn(Optional.of(outboxEvent));
        when(fieldsBuilder.build(documentId)).thenReturn(Optional.of(fields(documentId)));
        doThrow(new java.io.IOException("transient")).when(documentIndexer).upsertDocument(any(), anyLong());

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            poller.claimAndProcessOne();
        } finally {
            detachAppender(appender);
        }

        assertThat(outboxEvent.getAttempts()).isEqualTo(3);
        assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.ERROR).isEmpty();
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SearchIndexOutboxPoller.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SearchIndexOutboxPoller.class))
                .detachAppender(appender);
    }
}
