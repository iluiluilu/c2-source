package com.aismartstore.backend.search.index;

import com.aismartstore.backend.search.SearchIndexNames;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.micrometer.core.instrument.MeterRegistry;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch._types.Script;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SPEC-search-semantic T8 (indexing §4.3): writes {@code embedding} + {@code model_version} into the
 * existing {@code segment} documents as a bulk partial {@code update} (no {@code doc_as_upsert}, so it
 * never creates an ACL-less document). Idempotent - re-applying the same vector is harmless. This
 * class never touches ACL fields.
 *
 * <p>Note: {@code update} bumps the document's internal {@code _version}; a later lexical upsert with
 * the SAME {@code contentVersion} is therefore rejected as a (benign) version conflict by {@code
 * SegmentIndexer}, which keeps the vector; a new {@code contentVersion} always wins and the poller
 * re-requests the embedding (R4).
 */
@Component
public class SegmentVectorWriter {

    private static final Logger log = LoggerFactory.getLogger(SegmentVectorWriter.class);

    /** Per-call outcome: ids whose document does not exist yet (404) vs. ids that failed for another reason. */
    public record WriteResult(int updated, int skipped, List<String> missing, List<String> failed) {
        public WriteResult(int updated, List<String> missing, List<String> failed) {
            this(updated, 0, missing, failed);
        }

        public boolean ok() {
            return missing.isEmpty() && failed.isEmpty();
        }
    }

    /**
     * backend-multi-instance Task 13 (AC-17): conditional write. If the segment already carries an
     * {@code embed_text_hash} that differs from the hash this vector was computed for, the text changed after the
     * embed was requested - the vector is stale, so the update is a noop (a fresh embed.requested follows the new
     * lexical upsert). A segment without a hash (indexed before this feature) is written as before.
     */
    static final String CONDITIONAL_SCRIPT =
            "if (ctx._source.embed_text_hash != null && params.hash != null && ctx._source.embed_text_hash != params.hash) "
                    + "{ ctx.op = 'noop' } else { ctx._source.embedding = params.vec; ctx._source.model_version = params.model; "
                    + "if (params.hash != null) { ctx._source.embed_text_hash = params.hash } }";

    private final OpenSearchClient client;
    private final MeterRegistry meterRegistry;

    @Autowired
    public SegmentVectorWriter(OpenSearchClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    /** Test/no-metrics convenience. */
    public SegmentVectorWriter(OpenSearchClient client) {
        this(client, null);
    }

    /**
     * Vectors per bulk request. One 1024-dim vector is ~20 KB of JSON, so a whole large document in a
     * single bulk (thousands of segments) exceeds OpenSearch's indexing-pressure limit (10% of heap,
     * ~53 MB on a 512 MB node) and is rejected with 429; 100 keeps each request around 2 MB.
     */
    static final int BULK_CHUNK_SIZE = 100;

    public WriteResult write(Map<String, float[]> vectorsBySegmentId, String modelVersion) throws IOException {
        return write(vectorsBySegmentId, Map.of(), modelVersion);
    }

    /** @param hashBySegmentId SHA-256 of the embedded text per segment id (see {@link EmbedTextHash}); may be empty */
    public WriteResult write(
            Map<String, float[]> vectorsBySegmentId, Map<String, String> hashBySegmentId, String modelVersion)
            throws IOException {
        if (vectorsBySegmentId.isEmpty()) {
            return new WriteResult(0, 0, List.of(), List.of());
        }
        List<Map.Entry<String, float[]>> entries = new ArrayList<>(vectorsBySegmentId.entrySet());
        int updated = 0;
        int skipped = 0;
        List<String> missing = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (int from = 0; from < entries.size(); from += BULK_CHUNK_SIZE) {
            int to = Math.min(from + BULK_CHUNK_SIZE, entries.size());
            // Refresh once, with the last chunk: a refresh is index-wide, so it covers the earlier chunks too.
            BulkRequest.Builder builder =
                    new BulkRequest.Builder().refresh(to == entries.size() ? Refresh.True : Refresh.False);
            for (Map.Entry<String, float[]> entry : entries.subList(from, to)) {
                Map<String, JsonData> params = new HashMap<>();
                params.put("vec", JsonData.of(entry.getValue()));
                params.put("model", JsonData.of(modelVersion));
                String hash = hashBySegmentId.get(entry.getKey());
                if (hash != null) {
                    params.put("hash", JsonData.of(hash));
                }
                builder.operations(op -> op.update(u -> u
                        .index(SearchIndexNames.SEGMENT_ALIAS)
                        .id(entry.getKey())
                        .script(Script.of(s -> s.inline(
                                is -> is.lang("painless").source(CONDITIONAL_SCRIPT).params(params))))));
            }
            BulkResponse response = client.bulk(builder.build());

            for (BulkResponseItem item : response.items()) {
                if (item.error() == null) {
                    if ("noop".equals(item.result())) {
                        skipped++;
                        log.info("segment {} vector write skipped: embed_text_hash changed since the embed was requested",
                                item.id());
                        if (meterRegistry != null) {
                            meterRegistry.counter("semantic.vector.write.skipped").increment();
                        }
                    } else {
                        updated++;
                    }
                } else if (item.status() == 404) {
                    missing.add(item.id());
                } else {
                    failed.add(item.id());
                    log.error("segment {} vector update failed: {}", item.id(), item.error().reason());
                }
            }
        }
        return new WriteResult(updated, skipped, missing, failed);
    }
}
