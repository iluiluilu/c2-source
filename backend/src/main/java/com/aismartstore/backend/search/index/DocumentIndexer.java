package com.aismartstore.backend.search.index;

import com.aismartstore.backend.search.SearchIndexNames;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch._types.VersionType;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.transport.httpclient5.ResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * search-fulltext-04-dong-bo-chi-muc.md Task 2 - single-document upsert/
 * update_by_query/delete helper for the {@code document} index (the {@code
 * segment} twin is {@link SegmentIndexer}). One document at a time only -
 * every event this module reacts to (mục 4.2) touches exactly one {@code
 * document_id}.
 */
@Component
public class DocumentIndexer {

    private static final Logger log = LoggerFactory.getLogger(DocumentIndexer.class);

    private static final String VERSION_CONFLICT_ERROR_TYPE = "version_conflict_engine_exception";

    private final OpenSearchClient client;

    public DocumentIndexer(OpenSearchClient client) {
        this.client = client;
    }

    /**
     * Upserts {@code source} (must include {@code document_id}) with {@code
     * externalVersion} as the OpenSearch external version.
     *
     * <p>bugs/bug-002-search-index-version-conflict.md mục 5.2 - the caller
     * (indirectly, {@code DocumentIndexFieldsBuilder}) now computes {@code
     * externalVersion} from the document's actual data freshness (GREATEST of
     * every timestamp that can affect an indexed field), never from {@code
     * outbox_event.id} (queue processing order - could regress and reject a
     * genuinely fresher write with a same-or-lower value on retry). Version
     * type is {@link VersionType#ExternalGte} (accepts {@code >=}, not just
     * {@code >}) precisely so a retry that recomputes the exact same value
     * (nothing changed in Postgres between attempts) re-applies cleanly
     * instead of conflicting with its own prior partial success - the actual
     * trigger confirmed on stg for BUG-002.
     *
     * @return true if the write applied (false only on a benign version
     *         conflict - a genuinely newer/equal write already wrote this
     *         document)
     */
    public boolean upsertDocument(Map<String, Object> source, long externalVersion) throws IOException {
        String documentId = String.valueOf(source.get("document_id"));
        try {
            client.index(idx -> idx
                    .index(SearchIndexNames.DOCUMENT_ALIAS)
                    .id(documentId)
                    .document(source)
                    .versionType(VersionType.ExternalGte)
                    .version(externalVersion)
                    .refresh(Refresh.True));
            return true;
        } catch (OpenSearchException e) {
            if (isVersionConflict(e)) {
                log.warn("[BUG-002] document {} upsert skipped by external versioning "
                        + "(a newer-or-equal version is already indexed) - benign, see "
                        + "bugs/bug-002-search-index-version-conflict.md", documentId);
                return false;
            }
            throw e;
        } catch (ResponseException e) {
            // bugs/bug-002-search-index-version-conflict.md mục 5.1b - this is the exact class
            // mismatch that caused BUG-002: a 409 version_conflict_engine_exception from this
            // single-document index() call can surface as this transport-level exception instead
            // of OpenSearchException above (confirmed on stg, opensearch-java 2.17.0). Recognizing
            // it here too keeps the same benign-skip contract regardless of which exception type
            // the client happens to throw for a given response.
            if (isVersionConflict(e)) {
                log.warn("[BUG-002] document {} upsert skipped via transport-level ResponseException "
                        + "(a newer-or-equal version is already indexed) - benign, see "
                        + "bugs/bug-002-search-index-version-conflict.md", documentId);
                return false;
            }
            throw e;
        }
    }

    /** search-fulltext-04-dong-bo-chi-muc.md §4.2 bước 1/5 - same versioned-script mechanism as {@link SegmentIndexer#updateByQuery}. */
    public long updateByQuery(UUID documentId, String script, Map<String, JsonData> params) throws IOException {
        var response = client.updateByQuery(u -> u
                .index(SearchIndexNames.DOCUMENT_ALIAS)
                .query(documentIdQuery(documentId))
                .script(s -> s.inline(is -> is.lang("painless").source(script).params(params)))
                .refresh(true)
                .conflicts(org.opensearch.client.opensearch._types.Conflicts.Proceed));
        return response.updated() != null ? response.updated() : 0L;
    }

    /** SPEC-search-semantic T10 ({@code folder.moved}) - same partial update but selected by an arbitrary filter (e.g. {@code folder_id}). */
    public long updateByQuery(Query filter, String script, Map<String, JsonData> params) throws IOException {
        var response = client.updateByQuery(u -> u
                .index(SearchIndexNames.DOCUMENT_ALIAS)
                .query(filter)
                .script(s -> s.inline(is -> is.lang("painless").source(script).params(params)))
                .refresh(true)
                .conflicts(org.opensearch.client.opensearch._types.Conflicts.Proceed));
        return response.updated() != null ? response.updated() : 0L;
    }

    /**
     * trash-search-index-cleanup.md §4.2/§4.5 - drops every document in
     * {@code documentIds} from the index in one {@code deleteByQuery} call
     * ({@code terms} filter on {@code document_id}, which every {@code
     * document} index doc also carries - see {@code documentFields()} -
     * despite the index's own {@code _id} already being the document id;
     * using a filter rather than the {@code _id}-keyed delete API avoids
     * looping once per id). Never throws when no id matches ({@code
     * deleted=0}) - idempotent by construction, same contract as {@link
     * SegmentIndexer#deleteByDocumentIds}.
     */
    public void deleteByIds(List<UUID> documentIds) throws IOException {
        if (documentIds.isEmpty()) {
            return;
        }
        client.deleteByQuery(d -> d
                .index(SearchIndexNames.DOCUMENT_ALIAS)
                .query(documentIdsQuery(documentIds))
                .refresh(true));
    }

    private static Query documentIdQuery(UUID documentId) {
        return Query.of(q -> q.term(t -> t.field("document_id").value(FieldValue.of(documentId.toString()))));
    }

    private static Query documentIdsQuery(List<UUID> documentIds) {
        List<FieldValue> values = documentIds.stream().map(id -> FieldValue.of(id.toString())).toList();
        return Query.of(q -> q.terms(t -> t.field("document_id").terms(tf -> tf.value(values))));
    }

    private static boolean isVersionConflict(OpenSearchException e) {
        return e.error() != null && VERSION_CONFLICT_ERROR_TYPE.equals(e.error().type());
    }

    /**
     * bugs/bug-002-search-index-version-conflict.md mục 5.1b -
     * {@link ResponseException} carries no parsed {@code ErrorCause} the way
     * {@link OpenSearchException} does, only the raw HTTP status + response
     * body baked into {@link ResponseException#getMessage()} by {@code
     * buildMessage()} - checking both the status (409) and the body text is
     * the most precise match available without re-parsing the body's JSON
     * ourselves for a single boolean check.
     */
    private static boolean isVersionConflict(ResponseException e) {
        return e.status() == 409
                && e.getMessage() != null
                && e.getMessage().contains(VERSION_CONFLICT_ERROR_TYPE);
    }
}
