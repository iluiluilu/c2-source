package com.aismartstore.backend.search.index;

import com.aismartstore.backend.search.SearchIndexNames;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch._types.VersionType;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * search-fulltext-04-dong-bo-chi-muc.md Task 2 - bulk upsert/delete helper
 * for the {@code segment} index, shared by {@link SearchIndexOutboxPoller}
 * and (per the task's own wording) test fixtures that need to seed/tear down
 * segments the same way the real indexer does, instead of poking the raw
 * {@link OpenSearchClient} with ad-hoc maps.
 */
@Component
public class SegmentIndexer {

    private static final Logger log = LoggerFactory.getLogger(SegmentIndexer.class);

    /** OpenSearch's own error type string for a version conflict (§4.2 intro: expected/benign, not a poller failure). */
    private static final String VERSION_CONFLICT_ERROR_TYPE = "version_conflict_engine_exception";

    private final OpenSearchClient client;

    /**
     * search-fulltext-04-dong-bo-chi-muc.md §4.4.2 - the cap this class used
     * to lack entirely: one run's segments all went into a single {@code
     * BulkRequest}, with no limit on segments per run anywhere in the system
     * while uploads accept files up to 2GB. That runs into {@code
     * OpenSearchClientConfig}'s 30s response timeout long before it reaches
     * OpenSearch's own 100mb {@code http.max_content_length}, and a bulk that
     * times out fails identically on every retry - a permanently stuck row,
     * which (before §4.4.1) meant a permanently stuck queue.
     */
    private final int chunkSize;

    public SegmentIndexer(
            OpenSearchClient client, @Value("${app.search-index.bulk-chunk-size:500}") int chunkSize) {
        this.client = client;
        this.chunkSize = chunkSize;
    }

    /**
     * Upserts every {@code source} map (already fully shaped as the {@code
     * segment} mapping expects - {@code segment_id} keyed) with {@code
     * externalVersion} as the OpenSearch {@code version}/{@code
     * version_type=external} (search-fulltext-01 §2.1's {@code index_version}
     * contract - the caller uses {@code outbox_event.id}, monotonic and
     * already available, as this value). A conflict (a newer version already
     * indexed) is logged at debug and otherwise ignored - it means a
     * later event already overwrote this one, exactly the "chống ghi đè lùi"
     * this mechanism exists for, not a real failure.
     *
     * @return true if every non-version-conflict operation succeeded (the
     *         caller only marks the outbox row {@code processed_at} on true)
     */
    public boolean upsertSegments(List<Map<String, Object>> sources, long externalVersion) throws IOException {
        if (sources.isEmpty()) {
            return true;
        }

        // §4.4.2 - split into bounded chunks rather than one unbounded request.
        // Retrying a partially-applied set is safe without any extra bookkeeping:
        // every operation carries the same external version, so chunks that already
        // landed come back as version_conflict_engine_exception, which
        // hasNonVersionConflictErrors deliberately treats as benign.
        boolean allOk = true;
        for (int from = 0; from < sources.size(); from += chunkSize) {
            List<Map<String, Object>> chunk = sources.subList(from, Math.min(from + chunkSize, sources.size()));
            if (!upsertChunk(chunk, externalVersion)) {
                // Keep going: the caller retries the whole event anyway, and a
                // later chunk may well be the one that succeeds. Returning early
                // would leave more of the run un-indexed for no benefit.
                allOk = false;
            }
        }
        return allOk;
    }

    private boolean upsertChunk(List<Map<String, Object>> chunk, long externalVersion) throws IOException {
        BulkRequest.Builder builder = new BulkRequest.Builder().refresh(Refresh.True);
        for (Map<String, Object> source : chunk) {
            String segmentId = String.valueOf(source.get("segment_id"));
            builder.operations(op -> op.index(idx -> idx
                    .index(SearchIndexNames.SEGMENT_ALIAS)
                    .id(segmentId)
                    .document(source)
                    // bugs/bug-002-search-index-version-conflict.md muc 5.2 - ExternalGte (khong
                    // phai External) dong bo voi DocumentIndexer.upsertDocument: chap nhan retry
                    // gui lai dung version cu (khong doi vi Postgres khong doi giua cac lan thu).
                    .versionType(VersionType.ExternalGte)
                    .version(externalVersion)));
        }

        // search-fulltext-04-dong-bo-chi-muc.md §7 rui ro - bulk-chunk-size 500 la [DX],
        // chua do tren tai lieu that. round-trip (client-side) la con so can doi chieu voi
        // OpenSearchClientConfig.RESPONSE_TIMEOUT_SECONDS=30; took (server-side, tu chinh
        // OpenSearch tra ve) tach rieng phan xu ly khoi do tre mang. DEBUG (khong phai INFO)
        // vi day la 1 dong/chunk - bat len khi thuc su can do p95 tren tai lieu vai tram trang
        // that, khong de mac dinh spam log san xuat.
        long startNanos = System.nanoTime();
        BulkResponse response = client.bulk(builder.build());
        if (log.isDebugEnabled()) {
            long roundTripMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.debug("segment bulk upsert: {} operations, {} ms round-trip, {} ms server-side (took)",
                    chunk.size(), roundTripMs, response.took());
        }
        return !hasNonVersionConflictErrors(response);
    }

    /**
     * search-fulltext-04-dong-bo-chi-muc.md §4.2 bước 4 - drops every segment
     * of a superseded run from the index (the run's rows themselves stay in
     * Postgres; only the OpenSearch copy is removed since it is no longer
     * "active").
     */
    public void deleteByRunId(UUID runId) throws IOException {
        client.deleteByQuery(d -> d
                .index(SearchIndexNames.SEGMENT_ALIAS)
                .query(runIdQuery(runId))
                .refresh(true));
    }

    /**
     * trash-search-index-cleanup.md §4.2/§4.5 - drops every segment (content/
     * note/title) of every document in {@code documentIds} from the index, in
     * one {@code deleteByQuery} call using a {@code terms} filter for the
     * whole batch (never a loop calling the API once per id - up to {@code
     * TrashService.MAX_BATCH_SIZE}=500 ids/event would otherwise mean up to
     * 500 sequential round-trips for this call alone, the "poison event" risk
     * the spec's §7 rủi ro 3 rejected). Used by {@code
     * SearchIndexOutboxPoller#handleDocumentTrashed}/{@code
     * #handleDocumentPurged}. A no-op (no exception, {@code deleted=0}) when
     * none of the ids are currently in the index - idempotent by construction.
     */
    public void deleteByDocumentIds(List<UUID> documentIds) throws IOException {
        if (documentIds.isEmpty()) {
            return;
        }
        client.deleteByQuery(d -> d
                .index(SearchIndexNames.SEGMENT_ALIAS)
                .query(documentIdsQuery(documentIds))
                .refresh(true));
    }

    /** Used by {@code document.classification.changed}/{@code document.acl.changed} (§4.2 bước 1/5). */
    public long updateByQuery(Query filter, String script, Map<String, JsonData> params, int requestsPerSecond)
            throws IOException {
        var response = client.updateByQuery(u -> u
                .index(SearchIndexNames.SEGMENT_ALIAS)
                .query(filter)
                .script(s -> s.inline(is -> is.lang("painless").source(script).params(params)))
                .requestsPerSecond((long) requestsPerSecond)
                .refresh(true)
                .conflicts(org.opensearch.client.opensearch._types.Conflicts.Proceed));
        return response.updated() != null ? response.updated() : 0L;
    }

    private static Query runIdQuery(UUID runId) {
        return Query.of(q -> q.term(t -> t.field("run_id").value(FieldValue.of(runId.toString()))));
    }

    private static Query documentIdsQuery(List<UUID> documentIds) {
        List<FieldValue> values = documentIds.stream().map(id -> FieldValue.of(id.toString())).toList();
        return Query.of(q -> q.terms(t -> t.field("document_id").terms(tf -> tf.value(values))));
    }

    private static boolean hasNonVersionConflictErrors(BulkResponse response) {
        if (!response.errors()) {
            return false;
        }
        boolean realError = false;
        for (BulkResponseItem item : response.items()) {
            if (item.error() == null) {
                continue;
            }
            String type = item.error().type();
            if (VERSION_CONFLICT_ERROR_TYPE.equals(type)) {
                log.debug(
                        "segment {} bulk upsert skipped by external versioning (a newer version is already indexed)",
                        item.id());
                continue;
            }
            realError = true;
            log.error("segment {} bulk upsert failed: {}", item.id(), item.error().reason());
        }
        return realError;
    }
}
