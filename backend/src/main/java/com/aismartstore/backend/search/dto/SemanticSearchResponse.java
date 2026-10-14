package com.aismartstore.backend.search.dto;

import java.util.List;

/**
 * SPEC-search-semantic §2. {@code degradedMode}: {@code none|lexical_only|semantic_only}.
 * {@code insufficientEvidence} = nothing passed the evidence threshold (AC-3) - a normal 200, never an error.
 * {@code hasMore}: there are more results than the 2 viewable pages (UI suggests narrowing, AC-6.1).
 */
public record SemanticSearchResponse(
        List<SemanticResult> results,
        String degradedMode,
        boolean insufficientEvidence,
        long totalApprox,
        boolean hasMore,
        int maxPages) {

    public static final String DEGRADED_NONE = "none";
    public static final String DEGRADED_LEXICAL_ONLY = "lexical_only";
    public static final String DEGRADED_SEMANTIC_ONLY = "semantic_only";
    public static final int MAX_PAGES = 2;
}
