package com.aismartstore.backend.search.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /search/semantic} body (SPEC-search-semantic §2/§3). Deliberately has no {@code mode},
 * {@code minScore} or "unfiltered" flag (AC-1.2, AC-3.2, AC-4): unknown JSON fields are ignored.
 * {@code page} is 0 or 1 only (AC-6, max 2 pages).
 */
public record SemanticSearchRequest(
        @NotBlank @Size(max = 256) String query,
        SearchFilters filters,
        @Min(0) @Max(1) Integer page,
        @Min(1) @Max(50) Integer size) {
}
