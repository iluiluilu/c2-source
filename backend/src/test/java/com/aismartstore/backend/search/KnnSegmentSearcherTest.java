package com.aismartstore.backend.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aismartstore.backend.search.dto.SearchFilters;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.core.SearchRequest;

/**
 * SPEC-search-semantic T11 / AC-4.1 (structure half): the scope + FR-23 filter lives INSIDE the knn
 * clause, never in post_filter or an outer bool; fail-closed without a scope.
 */
class KnnSegmentSearcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ScopeContext ctx = new ScopeContext("user-a", List.of(UUID.randomUUID().toString()));

    private static String serialize(Object value) {
        JacksonJsonpMapper mapper = new JacksonJsonpMapper();
        java.io.StringWriter out = new java.io.StringWriter();
        try (jakarta.json.stream.JsonGenerator generator = mapper.jsonProvider().createGenerator(out)) {
            mapper.serialize(value, generator);
        }
        return out.toString();
    }

    private JsonNode json(SearchRequest request) throws Exception {
        return MAPPER.readTree(serialize(request));
    }

    @Test
    void filterIsInsideTheKnnClauseAndThereIsNoPostFilter() throws Exception {
        float[] vector = new float[1024];
        JsonNode root = json(KnnSegmentSearcher.buildRequest(vector, 50, ctx, null, "v1"));

        assertThat(root.has("post_filter")).isFalse();
        JsonNode knn = root.at("/query/knn/embedding");
        assertThat(knn.isMissingNode()).isFalse();
        assertThat(knn.get("k").asInt()).isEqualTo(50);
        assertThat(knn.get("vector")).hasSize(1024);
        // the query root is knn itself - no bool wrapper outside it that could carry a (wrong) filter
        assertThat(root.at("/query").fieldNames().next()).isEqualTo("knn");
        assertThat(knn.at("/filter/bool/filter")).isNotEmpty();
        assertThat(root.get("size").asInt()).isEqualTo(50);
    }

    @Test
    void filterCarriesTheScopeBuilderClauseModelVersionAndExcludesSed() throws Exception {
        JsonNode root = json(KnnSegmentSearcher.buildRequest(new float[1024], 10, ctx, null, "qwen-v1"));
        String filter = root.at("/query/knn/embedding/filter").toString();

        // exactly what ScopeFilterBuilder emits for this ctx
        String scope = MAPPER.readTree(serialize(ScopeFilterBuilder.build(ctx))).toString();
        assertThat(filter).contains(scope);
        assertThat(filter).contains("\"owner_user_id\"").contains("\"scope_group_ids\"");
        assertThat(filter).contains("\"model_version\"").contains("qwen-v1");
        assertThat(filter).contains("must_not").contains("\"content_source\"").contains("sed");
    }

    @Test
    void sameFr23ClausesAsTheLexicalBranchAreInTheKnnFilter() throws Exception {
        UUID folder = UUID.randomUUID();
        SearchFilters filters = new SearchFilters(
                "Quyết định", List.of("tag-1"), "AND", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1),
                List.of(), folder, List.of("document"), "manual");
        JsonNode root = json(KnnSegmentSearcher.buildRequest(new float[1024], 10, ctx, filters, "v1"));
        String filter = root.at("/query/knn/embedding/filter").toString();

        for (var clause : SearchQueryBuilder.buildFilterClauses(filters, ctx, List.of(), null)) {
            String expected = MAPPER.readTree(serialize(clause)).toString();
            assertThat(filter).as("clause %s", expected).contains(expected);
        }
        assertThat(filter).contains("doc_type").contains("topic_tags").contains("uploaded_at")
                .contains("folder_path_ids").contains(folder.toString()).contains("media_type").contains("confirm_source");
    }

    @Test
    void groupIdsOutsideTheCallersScopeAreDroppedSilently() throws Exception {
        UUID outsider = UUID.randomUUID();
        SearchFilters filters = new SearchFilters(null, null, null, null, null, List.of(outsider), null, null, null);
        JsonNode root = json(KnnSegmentSearcher.buildRequest(new float[1024], 10, ctx, filters, "v1"));

        assertThat(root.toString()).doesNotContain(outsider.toString());
    }

    @Test
    void aMissingScopeContextIsRefusedNeverAnUnfilteredQuery() {
        assertThatThrownBy(() -> KnnSegmentSearcher.buildRequest(new float[1024], 10, null, null, "v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmptyGroupListStillFiltersByOwner() throws Exception {
        JsonNode root = json(KnnSegmentSearcher.buildRequest(
                new float[1024], 10, new ScopeContext("solo", List.of()), null, "v1"));
        assertThat(root.at("/query/knn/embedding/filter").toString()).contains("owner_user_id").contains("solo");
    }
}
