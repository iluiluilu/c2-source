package com.aismartstore.backend.search.index;

import com.aismartstore.backend.digitization.results.ContentSegment;
import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.notes.DocumentNote;
import com.aismartstore.backend.notes.DocumentNoteRepository;
import com.aismartstore.backend.outbox.OutboxEvent;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.config.EmbeddingProperties;
import com.aismartstore.backend.config.SemanticProperties;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.opensearch.client.json.JsonData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * search-fulltext-04-dong-bo-chi-muc.md §4.2 - the "giải pháp tạm thời" indexer:
 * polls {@code outbox_event} directly (no shared outbox-relay/RabbitMQ consumer
 * exists yet, see spec §7 rủi ro 1) and reacts to the 9 event types the search
 * index must stay in sync with (the original 5 - classification/note/ingested/
 * review-confirmed-or-reconfirmed/acl-changed - plus trash/restore/purge, added
 * by trash-search-index-cleanup.md after the original 5 were found to leave
 * trashed/restored/purged documents permanently in the index, see that spec's
 * §0). One row claimed and fully processed per transaction (never a whole
 * batch in one transaction) so a single OpenSearch failure only ever blocks/
 * retries its own row, never the rest of the batch.
 */
@Component
public class SearchIndexOutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexOutboxPoller.class);

    static final String EVENT_CLASSIFICATION_CHANGED = "document.classification.changed";
    static final String EVENT_NOTE_CHANGED = "document.note.changed";
    static final String EVENT_DOCUMENT_INGESTED = "document.ingested";
    static final String EVENT_REVIEW_CONFIRMED = "review.confirmed";
    static final String EVENT_REVIEW_RECONFIRMED = "review.reconfirmed";
    static final String EVENT_ACL_CHANGED = "document.acl.changed";
    /** trash-search-index-cleanup.md §4.1 - {@code TrashService#trash}, 1..N document ids per event. */
    static final String EVENT_DOCUMENT_TRASHED = "document.trash";
    /** trash-search-index-cleanup.md §4.1 - {@code TrashService#restore}, 1 document id per event. */
    static final String EVENT_DOCUMENT_RESTORED = "document.restored";
    /** trash-search-index-cleanup.md §4.1 - {@code TrashPurgeService}, 1 document id per event. */
    static final String EVENT_DOCUMENT_PURGED = "document.purged";
    /**
     * SPEC-search-semantic T10 (Q-3/Q-10): {@code WarehouseService#moveFolder}. Payload carries only
     * {@code folderId} (+ {@code movedAt}, {@code movedBySub}) - the new path is always re-read from
     * PostgreSQL, never trusted from the payload.
     */
    static final String EVENT_FOLDER_MOVED = "folder.moved";

    /** Package-private: {@code SearchIndexReconciliationJob} scopes its parked-row alert (§4.4.4)
     * to exactly the event types this poller is responsible for. */
    static final List<String> HANDLED_EVENT_TYPES = List.of(
            EVENT_CLASSIFICATION_CHANGED,
            EVENT_NOTE_CHANGED,
            EVENT_DOCUMENT_INGESTED,
            EVENT_REVIEW_CONFIRMED,
            EVENT_REVIEW_RECONFIRMED,
            EVENT_ACL_CHANGED,
            EVENT_DOCUMENT_TRASHED,
            EVENT_DOCUMENT_RESTORED,
            EVENT_DOCUMENT_PURGED,
            EVENT_FOLDER_MOVED);

    /** §4.2 intro - one polling tick claims at most this many rows (one at a time, one transaction each). */
    private static final int BATCH_LIMIT = 200;
    /** §4.2 bước 1 - "throttle [ĐX] 500" for the (potentially large) classification `update_by_query`. */
    private static final int CLASSIFICATION_UPDATE_THROTTLE_RPS = 500;
    /** OpenSearch's own "unthrottled" sentinel for {@code requests_per_second} (update_by_query/reindex APIs). */
    private static final int NO_THROTTLE_RPS = -1;

    /** §4.2 bước 2 - fixed, never exposed in any API response (SegmentDocument has no getter consumers rely on for it). */
    private static final int NOTE_SEGMENT_INDEX = -1;
    private static final int TITLE_SEGMENT_INDEX = -2;

    private final OutboxEventRepository outboxEventRepository;
    private final DocumentIndexFieldsBuilder fieldsBuilder;
    private final SegmentIndexer segmentIndexer;
    private final DocumentIndexer documentIndexer;
    private final DocumentNoteRepository documentNoteRepository;
    private final ContentSegmentRepository contentSegmentRepository;
    private final DigitizationRunRepository digitizationRunRepository;

    /**
     * §4.2 intro's "nếu attempts vượt ngưỡng [ĐX] 20, log cảnh báo mức ERROR",
     * which §4.4.1 promotes from a pure logging threshold into the claim
     * query's own cut-off: at this many attempts a row is parked (see {@link
     * OutboxEventRepository#claimNextForIndexing}). The two must stay the
     * same number - the ERROR below is the one and only notification that a
     * row has just been parked, so a threshold lower than the query's would
     * cry wolf, and a higher one would park rows in silence.
     */
    private final int maxAttempts;

    /**
     * SPEC-search-semantic collaborators - setter-injected (optional) so the pre-existing
     * constructor contract of this class is unchanged. {@code embedPublisher == null} or {@code
     * semanticProperties.enabled() == false} means "no embed.requested" (flag off).
     */
    private EmbedRequestPublisher embedPublisher;
    private SemanticProperties semanticProperties;
    private WarehouseFolderRepository warehouseFolderRepository;

    @Autowired(required = false)
    void setSemanticCollaborators(
            EmbedRequestPublisher embedPublisher,
            SemanticProperties semanticProperties,
            WarehouseFolderRepository warehouseFolderRepository) {
        this.embedPublisher = embedPublisher;
        this.semanticProperties = semanticProperties;
        this.warehouseFolderRepository = warehouseFolderRepository;
    }

    /** Self-proxy so {@link #claimAndProcessOne()}'s {@code @Transactional} is honoured (same idiom as {@code GroupsService.self}). */
    private SearchIndexOutboxPoller self;

    public SearchIndexOutboxPoller(
            OutboxEventRepository outboxEventRepository,
            DocumentIndexFieldsBuilder fieldsBuilder,
            SegmentIndexer segmentIndexer,
            DocumentIndexer documentIndexer,
            DocumentNoteRepository documentNoteRepository,
            ContentSegmentRepository contentSegmentRepository,
            DigitizationRunRepository digitizationRunRepository,
            @Value("${app.search-index.max-attempts:20}") int maxAttempts) {
        this.maxAttempts = maxAttempts;
        this.outboxEventRepository = outboxEventRepository;
        this.fieldsBuilder = fieldsBuilder;
        this.segmentIndexer = segmentIndexer;
        this.documentIndexer = documentIndexer;
        this.documentNoteRepository = documentNoteRepository;
        this.contentSegmentRepository = contentSegmentRepository;
        this.digitizationRunRepository = digitizationRunRepository;
        this.self = this;
    }

    @Autowired
    void setSelf(@Lazy SearchIndexOutboxPoller self) {
        this.self = self;
    }

    /** backend-multi-instance Task 12: null in unit tests that build the poller by hand = no cross-instance lock. */
    private SearchIndexPollerLock pollerLock;

    /** backend-multi-instance Task 14: null = no hash annotation (unit tests that build the poller by hand). */
    private EmbeddingProperties embeddingProperties;

    @Autowired(required = false)
    void setEmbeddingProperties(EmbeddingProperties embeddingProperties) {
        this.embeddingProperties = embeddingProperties;
    }

    /** Adds {@code embed_text_hash} (same function + config as the embed consumer) to the lexical sources. */
    private void annotateEmbedHash(List<Map<String, Object>> sources) {
        if (embeddingProperties != null) {
            EmbedTextHash.annotate(
                    sources, embeddingProperties.chunkSizeTokens(), embeddingProperties.chunkOverlapTokens());
        }
    }

    @Autowired(required = false)
    void setPollerLock(SearchIndexPollerLock pollerLock) {
        this.pollerLock = pollerLock;
    }

    @Scheduled(fixedDelayString = "${app.search-index.poll-interval-ms:2000}")
    public void poll() {
        if (pollerLock == null) {
            pollLoop();
            return;
        }
        Optional<SearchIndexPollerLock.Handle> lock = pollerLock.tryAcquire();
        if (lock.isEmpty()) {
            log.debug("search index poller: another instance holds the poll lock, skipping tick");
            return;
        }
        try (SearchIndexPollerLock.Handle ignored = lock.get()) {
            pollLoop();
        }
    }

    private void pollLoop() {
        for (int i = 0; i < BATCH_LIMIT; i++) {
            boolean claimed = self.claimAndProcessOne();
            if (!claimed) {
                return;
            }
        }
    }

    /** @return true if a row was claimed (whether it succeeded or was left for retry) - false when the queue is empty. */
    @Transactional
    boolean claimAndProcessOne() {
        Optional<OutboxEvent> claimed = outboxEventRepository.claimNextForIndexing(HANDLED_EVENT_TYPES, maxAttempts);
        if (claimed.isEmpty()) {
            return false;
        }
        OutboxEvent event = claimed.get();
        try {
            self.processOneInNewTransaction(event);
            event.setProcessedAt(OffsetDateTime.now());
        } catch (RuntimeException | IOException e) {
            int attempts = event.getAttempts() + 1;
            event.setAttempts(attempts);
            event.setLastError(e.toString());
            if (attempts >= maxAttempts) {
                // §4.4.1 - this is the parking notification, and it fires exactly
                // once per poisoned row: the very next claim query already filters
                // this row out on `attempts < maxAttempts`, so there is no further
                // attempt to log. Before §4.4.1 the same branch re-fired on every
                // retry forever (~414 ERROR lines/minute measured on stg).
                log.error(
                        "outbox_event id={} eventType={} failed {} times - PARKED, will no longer be claimed; "
                                + "the search index is now knowingly missing this change and needs manual "
                                + "operator attention: {}",
                        event.getId(), event.getEventType(), attempts, e.toString(), e);
            } else {
                log.warn("outbox_event id={} eventType={} indexing attempt {} failed, will retry: {}",
                        event.getId(), event.getEventType(), attempts, e.toString());
            }
        }
        outboxEventRepository.save(event);
        return true;
    }

    /**
     * Bug fix 2026-09-09 (checklist muc 8.16): {@code processOne} can throw
     * for many reasons (a malformed document, a transient OpenSearch error,
     * ...), and any Spring Data repository call it makes (e.g. {@code
     * findById}) is itself wrapped in a {@code @Transactional} advice that
     * participates in the caller's transaction by default. When such a call
     * throws, Spring marks the WHOLE participating transaction rollback-only
     * - regardless of whether {@link #claimAndProcessOne} goes on to catch
     * the exception. That silently broke the attempts/last_error write a few
     * lines below (an {@code UnexpectedRollbackException} at commit,
     * swallowed by the scheduler's own error handler): the failing event's
     * {@code attempts} counter never advanced, so it retried every {@code
     * poll-interval-ms} forever, and - because {@code
     * OutboxEventRepository#claimNextForIndexing} claims strictly in {@code
     * id} order - blocked every other outbox event queued after it, forever.
     * Running {@code processOne} in its own {@code REQUIRES_NEW} transaction
     * confines any such rollback-only marking to this inner transaction
     * (which has no writes of its own worth keeping when it fails), leaving
     * {@link #claimAndProcessOne}'s transaction free to record the failure.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void processOneInNewTransaction(OutboxEvent event) throws IOException {
        processOne(event);
    }

    private void processOne(OutboxEvent event) throws IOException {
        String eventType = event.getEventType();
        Map<String, Object> payload = event.getPayload();

        switch (eventType) {
            case EVENT_CLASSIFICATION_CHANGED -> handleClassificationChanged(payload);
            case EVENT_NOTE_CHANGED -> handleNoteChanged(payload);
            case EVENT_DOCUMENT_INGESTED -> handleDocumentIngested(payload);
            case EVENT_REVIEW_CONFIRMED, EVENT_REVIEW_RECONFIRMED -> handleReviewConfirmed(payload);
            case EVENT_ACL_CHANGED -> handleAclChanged(payload);
            case EVENT_DOCUMENT_TRASHED -> handleDocumentTrashed(payload);
            case EVENT_DOCUMENT_RESTORED -> handleDocumentRestored(payload);
            case EVENT_DOCUMENT_PURGED -> handleDocumentPurged(payload);
            case EVENT_FOLDER_MOVED -> handleFolderMoved(payload);
            default -> log.warn("SearchIndexOutboxPoller: unhandled event_type '{}' (claim query bug?)", eventType);
        }
    }

    /** §4.2 bước 1 - AC-4/AC-6 story -04: re-reads current doc_type/topic_tags, update_by_query on both indices. */
    private void handleClassificationChanged(Map<String, Object> payload) throws IOException {
        UUID documentId = documentId(payload);
        Optional<DocumentIndexFieldsBuilder.Fields> fields = fieldsBuilder.build(documentId);
        if (fields.isEmpty()) {
            return; // document deleted/gone since the event was written - nothing to reflect.
        }
        // bugs/bug-002-search-index-version-conflict.md muc 5.2 - version = do moi du lieu that
        // (tinh lai moi lan build() chay), khong con la event.getId() (thu tu hang doi, khong an toan).
        long externalVersion = fields.get().contentVersion();

        // "" rather than null (Painless/JsonData have no clean null bridge here) - never
        // collides with a real doc_type label, and SearchServiceImpl only filters on a
        // non-blank docType, so an empty stored value behaves exactly like "unclassified".
        String docType = fields.get().docType() == null ? "" : fields.get().docType();
        Map<String, JsonData> params = Map.of(
                "docType", JsonData.of(docType),
                "topicTags", JsonData.of(fields.get().topicTags()),
                "newVersion", JsonData.of(externalVersion));
        String script = versionedSetScript(Set.of("doc_type", "topic_tags"));

        segmentIndexer.updateByQuery(documentIdFilter(documentId), script, params, CLASSIFICATION_UPDATE_THROTTLE_RPS);
        documentIndexer.updateByQuery(documentId, script, params);
    }

    /** §4.2 bước 2 - one synthesized `content_source='note'` segment per document, merging every note (chronological). */
    private void handleNoteChanged(Map<String, Object> payload) throws IOException {
        UUID documentId = documentId(payload);
        Optional<DocumentIndexFieldsBuilder.Fields> fields = fieldsBuilder.build(documentId);
        if (fields.isEmpty()) {
            return;
        }
        if (indexNoteSegmentIfAny(documentId, fields.get(), fields.get().contentVersion())) {
            publishEmbed(documentId, List.of(notesSegmentId(documentId)), EmbedRequestMessage.REASON_NOTE,
                    fields.get().contentVersion());
        }
    }

    /**
     * trash-search-index-cleanup.md §4.3 Task 4 - the body of the former
     * {@code handleNoteChanged}, extracted so {@link #handleDocumentRestored}
     * can rebuild the note segment the same way: merges every current note
     * (oldest first) into the single {@code content_source='note'} segment,
     * or leaves the index untouched if the document has no note.
     */
    private boolean indexNoteSegmentIfAny(UUID documentId, DocumentIndexFieldsBuilder.Fields f, long externalVersion)
            throws IOException {
        List<DocumentNote> notes = new ArrayList<>(documentNoteRepository.findByDocumentIdOrderByCreatedAtDesc(documentId));
        if (notes.isEmpty()) {
            return false; // no note left to reflect (no delete path exists today, but stay defensive).
        }
        java.util.Collections.reverse(notes); // oldest first - a natural reading order for the merged text.
        String mergedText = notes.stream().map(DocumentNote::getBody).reduce((a, b) -> a + "\n\n" + b).orElse("");

        Map<String, Object> source =
                baseSegmentFields(documentId, notesSegmentId(documentId), NOTE_SEGMENT_INDEX, f, externalVersion);
        source.put("text", mergedText);
        source.put("content_source", "note");
        source.put("confirm_source", null);
        source.put("run_id", null);
        source.put("position_type", "none");

        List<Map<String, Object>> noteSources = List.of(source);
        annotateEmbedHash(noteSources);
        segmentIndexer.upsertSegments(noteSources, externalVersion);
        return true;
    }

    /** §4.2 bước 3 - AC-2: upsert `document` + a `content_source='title'` segment right away, before any digitization result exists. */
    private void handleDocumentIngested(Map<String, Object> payload) throws IOException {
        UUID documentId = documentId(payload);
        Optional<DocumentIndexFieldsBuilder.Fields> fields = fieldsBuilder.build(documentId);
        if (fields.isEmpty()) {
            return;
        }
        indexDocumentAndTitleSegment(documentId, fields.get(), fields.get().contentVersion());
        publishEmbed(documentId, List.of(titleSegmentId(documentId)), EmbedRequestMessage.REASON_INGESTED,
                fields.get().contentVersion());
    }

    /**
     * trash-search-index-cleanup.md §4.3 Task 4 - the body of the former
     * {@code handleDocumentIngested}, extracted so {@link
     * #handleDocumentRestored} can rebuild both the {@code document} and its
     * title segment the same way.
     */
    private void indexDocumentAndTitleSegment(UUID documentId, DocumentIndexFieldsBuilder.Fields f, long externalVersion)
            throws IOException {
        Map<String, Object> documentSource = documentFields(f, externalVersion);
        documentIndexer.upsertDocument(documentSource, externalVersion);

        Map<String, Object> titleSegment =
                baseSegmentFields(documentId, titleSegmentId(documentId), TITLE_SEGMENT_INDEX, f, externalVersion);
        titleSegment.put("text", f.documentName());
        titleSegment.put("content_source", "title");
        titleSegment.put("confirm_source", null);
        titleSegment.put("run_id", null);
        titleSegment.put("position_type", "none");
        List<Map<String, Object>> titleSources = List.of(titleSegment);
        annotateEmbedHash(titleSources);
        segmentIndexer.upsertSegments(titleSources, externalVersion);
    }

    /**
     * §4.2 bước 4 - upserts every non-deleted {@code content_segment} of the
     * confirmed/reconfirmed run, plus `document`, then removes the segments
     * of whichever run this one just superseded (if any) from the index.
     */
    private void handleReviewConfirmed(Map<String, Object> payload) throws IOException {
        UUID documentId = documentId(payload);
        UUID runId = UUID.fromString(String.valueOf(payload.get("runId")));
        String task = String.valueOf(payload.get("task"));

        Optional<DocumentIndexFieldsBuilder.Fields> fields = fieldsBuilder.build(documentId);
        if (fields.isEmpty()) {
            return;
        }
        DocumentIndexFieldsBuilder.Fields f = fields.get();
        // bugs/bug-002-search-index-version-conflict.md muc 5.2 - version = do moi du lieu that
        // (bao gom ca content_segment.updated_at, vi handler nay ghi ca noi dung OCR/STT), khong
        // con la event.getId() (thu tu hang doi, khong an toan).
        long externalVersion = f.contentVersion();

        List<ContentSegment> segments = contentSegmentRepository.findByRunIdOrderBySegmentIndex(runId);
        String runConfirmSource = digitizationRunRepository.findById(runId)
                .map(DigitizationRun::getConfirmSource)
                .orElse(null);
        List<Map<String, Object>> confirmedSources =
                confirmedSegmentSources(segments, runConfirmSource, f, externalVersion);
        annotateEmbedHash(confirmedSources);
        segmentIndexer.upsertSegments(confirmedSources, externalVersion);

        Map<String, Object> documentSource = documentFields(f, externalVersion);
        documentIndexer.upsertDocument(documentSource, externalVersion);

        // R4: the lexical upsert above OVERWRITES each document, dropping its vector - always ask for the
        // same segments to be embedded again (and only those; `sed` etc. are filtered out).
        publishEmbed(documentId, embeddableIds(confirmedSources), EmbedRequestMessage.REASON_CONFIRMED, externalVersion);

        digitizationRunRepository
                .findByDocumentIdAndTaskAndReviewState(documentId, task, DigitizationRun.REVIEW_SUPERSEDED)
                .filter(other -> !other.getId().equals(runId))
                .ifPresent(other -> {
                    try {
                        segmentIndexer.deleteByRunId(other.getId());
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
    }

    /** §4.2 bước 5 - QĐ5 SA "hàng đợi ưu tiên cao": re-reads every current share, never applies just the one delta. */
    private void handleAclChanged(Map<String, Object> payload) throws IOException {
        UUID documentId = documentId(payload);
        Optional<DocumentIndexFieldsBuilder.Fields> fields = fieldsBuilder.build(documentId);
        if (fields.isEmpty()) {
            return;
        }
        // bugs/bug-002-search-index-version-conflict.md muc 5.2 - version = do moi du lieu that,
        // khong con la event.getId() (thu tu hang doi, khong an toan).
        long externalVersion = fields.get().contentVersion();

        Map<String, JsonData> params = Map.of(
                "scopeGroupIds", JsonData.of(fields.get().scopeGroupIds()),
                "newVersion", JsonData.of(externalVersion));
        String script = versionedSetScript(Set.of("scope_group_ids"));

        segmentIndexer.updateByQuery(documentIdFilter(documentId), script, params, NO_THROTTLE_RPS);
        documentIndexer.updateByQuery(documentId, script, params);
    }

    /**
     * trash-search-index-cleanup.md §4.2 - {@code payload.documentIds} may
     * hold 1..{@code TrashService.MAX_BATCH_SIZE} ids (a batch move-to-trash
     * from the UI), unlike every other handler above which only ever handles
     * one {@code documentId}/event. Deletes outright (§2 - no "isDeleted"
     * field, no query-time filter) rather than marking a field, so a restore
     * later has to rebuild from Postgres from scratch (see {@link
     * #handleDocumentRestored}).
     */
    private void handleDocumentTrashed(Map<String, Object> payload) throws IOException {
        deleteDocumentsFromIndex(documentIds(payload));
    }

    /**
     * trash-search-index-cleanup.md §4.3 - rebuilds the whole search footprint
     * of {@code payload.documentId} from Postgres from scratch (document +
     * title segment + every non-deleted confirmed content segment across all
     * tasks + the merged note segment, if any) - never an "un-delete", since
     * {@link #handleDocumentTrashed}/{@link #handleDocumentPurged} remove the
     * document from the index outright rather than marking a field.
     */
    private void handleDocumentRestored(Map<String, Object> payload) throws IOException {
        UUID documentId = documentId(payload);
        Optional<DocumentIndexFieldsBuilder.Fields> fields = fieldsBuilder.build(documentId);
        if (fields.isEmpty()) {
            return; // purged again right after being restored (race hiếm) - nothing left to reflect.
        }
        DocumentIndexFieldsBuilder.Fields f = fields.get();
        // bugs/bug-002-search-index-version-conflict.md muc 5.2 - version = do moi du lieu that,
        // khong con la event.getId() (thu tu hang doi, khong an toan).
        long externalVersion = f.contentVersion();

        indexDocumentAndTitleSegment(documentId, f, externalVersion);
        indexConfirmedSegmentsForAllTasks(documentId, f, externalVersion);
        indexNoteSegmentIfAny(documentId, f, externalVersion);
        // The whole footprint was rebuilt from scratch (no vectors) - embed the whole document again.
        publishEmbed(documentId, null, EmbedRequestMessage.REASON_RESTORED, externalVersion);
    }

    /**
     * trash-search-index-cleanup.md §4.4 - same delete path as {@link
     * #handleDocumentTrashed} (single-element batch), idempotent whether or
     * not a preceding {@code document.trash} event already ran: covers both
     * the normal trash-then-purge-after-30-days flow and a document purged
     * directly without ever going through trash (§4.4 point 2).
     */
    private void handleDocumentPurged(Map<String, Object> payload) throws IOException {
        deleteDocumentsFromIndex(List.of(documentId(payload)));
    }

    /** trash-search-index-cleanup.md §4.5 - shared by {@link #handleDocumentTrashed}/{@link #handleDocumentPurged}. */
    private void deleteDocumentsFromIndex(List<UUID> documentIds) throws IOException {
        segmentIndexer.deleteByDocumentIds(documentIds);
        documentIndexer.deleteByIds(documentIds);
    }

    /**
     * trash-search-index-cleanup.md §4.3 Task 4 - generalizes {@code
     * handleReviewConfirmed}'s per-run segment loop (see {@link
     * #confirmedSegmentSources}) to every confirmed run of {@code documentId}
     * across every task (OCR/STT/SED/summary), unlike {@code
     * handleReviewConfirmed} itself which only ever rebuilds the one run its
     * event names. One {@code upsertSegments} call for the whole document.
     */
    private void indexConfirmedSegmentsForAllTasks(
            UUID documentId, DocumentIndexFieldsBuilder.Fields f, long externalVersion) throws IOException {
        List<DigitizationRun> confirmedRuns =
                digitizationRunRepository.findByDocumentIdAndReviewState(documentId, DigitizationRun.REVIEW_CONFIRMED);
        List<Map<String, Object>> sources = new ArrayList<>();
        for (DigitizationRun run : confirmedRuns) {
            List<ContentSegment> segments = contentSegmentRepository.findByRunIdOrderBySegmentIndex(run.getId());
            sources.addAll(confirmedSegmentSources(segments, run.getConfirmSource(), f, externalVersion));
        }
        annotateEmbedHash(sources);
        segmentIndexer.upsertSegments(sources, externalVersion);
    }

    /**
     * digitization-review-01-ocr.md §2 (V28) - a soft-deleted segment is
     * never searchable. Shared by {@link #handleReviewConfirmed} (one run)
     * and {@link #indexConfirmedSegmentsForAllTasks} (every confirmed run).
     */
    private static List<Map<String, Object>> confirmedSegmentSources(
            List<ContentSegment> segments,
            String runConfirmSource,
            DocumentIndexFieldsBuilder.Fields f,
            long externalVersion) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (ContentSegment segment : segments) {
            if (segment.isDeleted()) {
                continue;
            }
            sources.add(contentSegmentFields(segment, runConfirmSource, f, externalVersion));
        }
        return sources;
    }

    /** trash-search-index-cleanup.md §4.2/§4.4 - {@code TrashService}/{@code TrashPurgeService} write ids as strings. */
    private static List<UUID> documentIds(Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) payload.get("documentIds");
        return ids.stream().map(UUID::fromString).toList();
    }

    // ---- search-semantic: embed.requested + folder.moved -------------------------------------

    private boolean semanticEnabled() {
        return embedPublisher != null && semanticProperties != null && semanticProperties.enabled();
    }

    /**
     * SPEC-search-semantic T7 (indexing §4.1): asks for the given segments to be embedded, only after
     * the lexical write of this very event succeeded. A publish failure throws, so the outbox row is
     * retried (idempotent). {@code segmentIds == null} = the whole document. Nothing is published for
     * an empty id list or when the semantic flag is off.
     */
    private void publishEmbed(UUID documentId, List<String> segmentIds, String reason, long version) {
        if (!semanticEnabled()) {
            return;
        }
        if (segmentIds != null && segmentIds.isEmpty()) {
            return;
        }
        embedPublisher.publish(documentId, segmentIds, reason, version);
    }

    /** Ids of the freshly upserted sources that are eligible for embedding (no `sed`, no blank text). */
    private static List<String> embeddableIds(List<Map<String, Object>> sources) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> source : sources) {
            Object text = source.get("text");
            if (EmbedTextBuilder.isEmbeddable(String.valueOf(source.get("content_source")))
                    && text != null
                    && !String.valueOf(text).isBlank()) {
                ids.add(String.valueOf(source.get("segment_id")));
            }
        }
        return ids;
    }

    /**
     * SPEC-search-semantic T10 (Q-3, Q-10): recomputes {@code folder_path_ids} for the moved folder and
     * every folder in its subtree from PostgreSQL, then partial-updates the {@code segment} and {@code
     * document} index entries filed directly in each of those folders (selected by their {@code
     * folder_id}). The update is unconditional and idempotent (always the current Postgres state), keeps
     * the vector, is throttled (requests_per_second) and independent of the semantic flag.
     */
    private void handleFolderMoved(Map<String, Object> payload) throws IOException {
        if (warehouseFolderRepository == null) {
            throw new IllegalStateException("folder.moved cannot be handled: WarehouseFolderRepository is not wired");
        }
        UUID folderId = UUID.fromString(String.valueOf(payload.get("folderId")));
        Optional<WarehouseFolder> moved = warehouseFolderRepository.findById(folderId);
        if (moved.isEmpty()) {
            return; // folder deleted since the event was written - nothing to reflect.
        }
        List<WarehouseFolder> subtree = new ArrayList<>();
        subtree.add(moved.get());
        subtree.addAll(warehouseFolderRepository.findDescendants(folderId));

        int rps = semanticProperties != null && semanticProperties.folderMoveThrottleRps() > 0
                ? semanticProperties.folderMoveThrottleRps()
                : CLASSIFICATION_UPDATE_THROTTLE_RPS;
        String script = "ctx._source.folder_path_ids = params.folderPathIds;";
        for (WarehouseFolder folder : subtree) {
            List<String> folderPathIds = new ArrayList<>();
            for (UUID ancestorId : folder.getPathIds()) {
                folderPathIds.add(ancestorId.toString());
            }
            folderPathIds.add(folder.getId().toString());

            Map<String, JsonData> params = Map.of("folderPathIds", JsonData.of(folderPathIds));
            org.opensearch.client.opensearch._types.query_dsl.Query filter =
                    org.opensearch.client.opensearch._types.query_dsl.Query.of(q -> q.term(t -> t.field("folder_id")
                            .value(org.opensearch.client.opensearch._types.FieldValue.of(folder.getId().toString()))));
            segmentIndexer.updateByQuery(filter, script, params, rps);
            documentIndexer.updateByQuery(filter, script, params);
        }
    }

    // ---- shared field builders -------------------------------------------------------------

    private static Map<String, Object> documentFields(DocumentIndexFieldsBuilder.Fields f, long externalVersion) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("document_id", f.documentId().toString());
        source.put("document_name", f.documentName());
        source.put("display_name", f.displayName());
        putCommonFields(source, f, externalVersion);
        return source;
    }

    private static Map<String, Object> baseSegmentFields(
            UUID documentId, String segmentId, int segmentIndex, DocumentIndexFieldsBuilder.Fields f, long externalVersion) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("segment_id", segmentId);
        source.put("document_id", documentId.toString());
        source.put("segment_index", segmentIndex);
        source.put("page_no", null);
        source.put("line_from", null);
        source.put("line_to", null);
        source.put("time_start_ms", null);
        source.put("time_end_ms", null);
        putCommonFields(source, f, externalVersion);
        return source;
    }

    private static Map<String, Object> contentSegmentFields(
            ContentSegment segment,
            String runConfirmSource,
            DocumentIndexFieldsBuilder.Fields f,
            long externalVersion) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("segment_id", segment.getId().toString());
        source.put("document_id", f.documentId().toString());
        source.put("segment_index", segment.getSegmentIndex());
        source.put("text", segment.getCurrentText());
        source.put("content_source", segment.getContentSource());
        // §2.1: the confirm_source of record is the run's (who confirmed it and how), never a per-segment guess.
        source.put("confirm_source", DigitizationRun.searchConfirmSource(runConfirmSource));
        source.put("run_id", segment.getRunId().toString());

        boolean timeBased = ContentSegment.SOURCE_STT.equals(segment.getContentSource())
                || ContentSegment.SOURCE_SED.equals(segment.getContentSource())
                || ContentSegment.SOURCE_VIDEO.equals(segment.getContentSource());
        if (timeBased) {
            source.put("position_type", "time");
            source.put("page_no", null);
            source.put("line_from", null);
            source.put("line_to", null);
            source.put("time_start_ms", segment.getCurrentTimeStartMs());
            source.put("time_end_ms", segment.getCurrentTimeEndMs());
        } else if (ContentSegment.SOURCE_OCR.equals(segment.getContentSource())) {
            source.put("position_type", "page");
            source.put("page_no", segment.getPageNo());
            source.put("line_from", segment.getLineFrom());
            source.put("line_to", segment.getLineTo());
            source.put("time_start_ms", null);
            source.put("time_end_ms", null);
        } else {
            // summary (or any future content_source with no finer position, FR-25 exception).
            source.put("position_type", "none");
            source.put("page_no", null);
            source.put("line_from", null);
            source.put("line_to", null);
            source.put("time_start_ms", null);
            source.put("time_end_ms", null);
        }
        putCommonFields(source, f, externalVersion);
        return source;
    }

    /**
     * search-fulltext-01-tim-kiem-co-ban.md §2.1 - {@code index_version} is set
     * here, once, for every {@code document}/{@code segment} write this class
     * makes: before this fix {@code contentSegmentFields} (the OCR/STT/summary
     * path) relied on this method alone and never got an {@code index_version}
     * at all, while every other caller set it by hand afterwards - a single
     * missed call away from silently reintroducing the same gap. Bulk upsert
     * still separately relies on OpenSearch's own {@code version_type=external}
     * (not this field) for its own conflict protection (see {@link
     * SegmentIndexer#upsertSegments}); this field is what {@link
     * #versionedSetScript}'s partial-update scripts compare against, so a full
     * upsert that leaves it unset effectively resets that comparison to "no
     * floor", letting a stale scripted update apply on top of newer content.
     */
    private static void putCommonFields(Map<String, Object> source, DocumentIndexFieldsBuilder.Fields f, long externalVersion) {
        source.put("doc_type", f.docType());
        source.put("topic_tags", f.topicTags());
        source.put("folder_id", f.folderId());
        source.put("folder_path_ids", f.folderPathIds());
        source.put("uploaded_at", f.uploadedAt() != null ? f.uploadedAt().toString() : null);
        source.put("media_type", f.mediaType());
        source.put("owner_user_id", f.ownerUserId());
        source.put("scope_group_ids", f.scopeGroupIds());
        source.put("acl_version", 1);
        source.put("index_version", externalVersion);
    }

    private static String versionedSetScript(Set<String> fieldNames) {
        StringBuilder script = new StringBuilder();
        script.append("if (params.newVersion > (ctx._source.index_version == null ? 0L : ctx._source.index_version)) { ");
        for (String field : fieldNames) {
            String paramName = camelCase(field);
            script.append("ctx._source.").append(field).append(" = params.").append(paramName).append("; ");
        }
        script.append("ctx._source.index_version = params.newVersion; ");
        // bugs/bug-003-versioned-set-script-invalid-noop.md - 'none' khong phai gia tri
        // ctx.op hop le trong _update_by_query (chi noop/index/delete), OpenSearch tu
        // choi voi illegal_argument_exception ngay khi version khong lon hon (ngay ca
        // khi bang nhau - tinh huong ngay cang pho bien sau fix BUG-002 vi contentVersion
        // dung chung cho nhieu event xu ly cung 1 tai lieu khi Postgres chua doi gi them).
        script.append("} else { ctx.op = 'noop'; }");
        return script.toString();
    }

    private static String camelCase(String snakeCaseField) {
        return switch (snakeCaseField) {
            case "doc_type" -> "docType";
            case "topic_tags" -> "topicTags";
            case "scope_group_ids" -> "scopeGroupIds";
            default -> snakeCaseField;
        };
    }

    private static org.opensearch.client.opensearch._types.query_dsl.Query documentIdFilter(UUID documentId) {
        return org.opensearch.client.opensearch._types.query_dsl.Query.of(q -> q.term(
                t -> t.field("document_id").value(org.opensearch.client.opensearch._types.FieldValue.of(documentId.toString()))));
    }

    private static String notesSegmentId(UUID documentId) {
        return documentId + ":note";
    }

    private static String titleSegmentId(UUID documentId) {
        return documentId + ":title";
    }

    private static UUID documentId(Map<String, Object> payload) {
        return UUID.fromString(String.valueOf(payload.get("documentId")));
    }
}
