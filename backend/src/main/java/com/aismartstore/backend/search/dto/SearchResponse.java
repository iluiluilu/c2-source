package com.aismartstore.backend.search.dto;

import java.util.List;

/**
 * search-fulltext-01-tim-kiem-co-ban.md §3.2 - {@code POST /search} response
 * body, extended by search-fulltext-02-tim-kiem-nang-cao.md §3.2 with {@link
 * #refineSuggestions()} (AC-12 - always {@code List.of()} outside the
 * "totalApprox = 0 with at least one active filter" case).
 */
public record SearchResponse(
        List<SearchHit> results,
        long totalApprox,
        int page,
        int size,
        String degradedMode,
        List<RefineSuggestion> refineSuggestions) {

    public static final String DEGRADED_NONE = "none";
    public static final String DEGRADED_OPENSEARCH_UNAVAILABLE = "opensearch_unavailable";
}
