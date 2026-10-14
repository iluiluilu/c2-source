package com.aismartstore.backend.search;

import com.aismartstore.backend.search.dto.SearchFilters;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.stereotype.Component;

/**
 * SPEC-search-semantic T11 (search-semantic-query.md §4.2): the vector branch - one k-NN request
 * against the {@code segment} alias with <b>efficient filtering</b>: the scope + FR-23 filter is
 * placed INSIDE the {@code knn} clause, never in {@code post_filter} or an outer {@code bool}
 * (those would cut the top-k after it was chosen - wrong recall and a leak risk). The filter is
 * the very same clause list the lexical branch uses ({@link SearchQueryBuilder#buildFilterClauses},
 * which starts with {@link ScopeFilterBuilder#build}), plus the current {@code model_version} and
 * "not {@code sed}" (AC-2.5).
 *
 * <p>Fail-closed: a null {@link ScopeContext} throws - there is no code path that builds a k-NN
 * query without a scope filter.
 */
@Component
public class KnnSegmentSearcher {

    static final String VECTOR_FIELD = "embedding";

    private final OpenSearchClient client;

    public KnnSegmentSearcher(OpenSearchClient client) {
        this.client = client;
    }

    /** @param modelVersion the currently configured {@code app.embedding.model-version}. */
    public List<VectorHit> search(
            float[] vector, int k, ScopeContext ctx, SearchFilters filters, String modelVersion) throws IOException {
        SearchRequest request = buildRequest(vector, k, ctx, filters, modelVersion);
        SearchResponse<SegmentDocument> response = client.search(request, SegmentDocument.class);

        List<VectorHit> hits = new ArrayList<>();
        for (Hit<SegmentDocument> hit : response.hits().hits()) {
            SegmentDocument source = hit.source();
            String segmentId = hit.id();
            UUID documentId = source == null ? null : tryParse(source.getDocumentId());
            if (segmentId == null || documentId == null || hit.score() == null) {
                continue;
            }
            hits.add(new VectorHit(segmentId, documentId, KnnScore.toCosine(hit.score())));
        }
        return hits;
    }

    /** Public + static so the "filter lives inside knn, no post_filter" structure is unit-testable. */
    public static SearchRequest buildRequest(
            float[] vector, int k, ScopeContext ctx, SearchFilters filters, String modelVersion) {
        if (ctx == null) {
            throw new IllegalArgumentException("A resolved ScopeContext is mandatory for a k-NN query (fail-closed)");
        }
        GroupIdIntersection groupIds = GroupIdIntersection.resolve(filters == null ? null : filters.groupIds(), ctx);

        List<Query> clauses = new ArrayList<>(SearchQueryBuilder.buildFilterClauses(filters, ctx, groupIds.effective(), null));
        clauses.add(Query.of(q -> q.term(t -> t.field("model_version").value(FieldValue.of(modelVersion)))));
        clauses.add(Query.of(q -> q.bool(b -> b.mustNot(
                Query.of(m -> m.term(t -> t.field("content_source").value(FieldValue.of("sed"))))))));
        Query filter = Query.of(q -> q.bool(b -> b.filter(clauses)));

        return new SearchRequest.Builder()
                .index(SearchIndexNames.SEGMENT_ALIAS)
                .size(k)
                .source(s -> s.filter(f -> f.includes("document_id")))
                .query(Query.of(q -> q.knn(kq -> kq.field(VECTOR_FIELD).vector(vector).k(k).filter(filter))))
                .build();
    }

    private static UUID tryParse(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
