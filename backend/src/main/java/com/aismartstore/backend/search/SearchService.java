package com.aismartstore.backend.search;

import com.aismartstore.backend.search.dto.SearchFilters;
import com.aismartstore.backend.search.query.LexicalHit;
import java.util.List;

/**
 * search-fulltext-04-dong-bo-chi-muc.md §3 - the <b>only</b> public contract
 * {@code search-semantic} is allowed to call into this module through
 * (search-fulltext.md §5, FR-24). {@link SearchServiceImpl} - the {@code
 * POST /search} orchestrator itself - is the sole implementation; everything
 * else about it (scope resolution, filters, ACL double-check, degraded mode,
 * audit) is deliberately not part of this interface's surface.
 */
public interface SearchService {

    /**
     * Runs the plain lexical (BM25) branch of {@link SearchQueryBuilder}
     * against {@code segment} - no {@code collapse} (RRF fuses by {@code
     * segment_id} directly), {@code size = topK} - and returns the raw hits
     * with <b>no</b> ACL double-check.
     *
     * <p>{@code scopeCtx} must already be resolved by the caller (this method
     * never calls {@code ScopeContext.resolve} itself); the {@code
     * scope_group_ids ∩ filters.groupIds()} intersection is computed here,
     * exactly like {@code POST /search} does. The caller ({@code
     * SemanticSearchService}) is responsible for running every returned
     * {@link LexicalHit} through {@code SearchResultHydrator} (or an
     * equivalent Postgres-backed check) before ever returning a result to an
     * end user - "chỉ mục là bộ lọc thứ nhất, DB là bộ lọc quyết định".
     *
     * @param query    the keyword query - never blank in practice (a blank
     *                 query short-circuits to an empty list rather than
     *                 querying OpenSearch, since a lexical-only branch with no
     *                 keyword has no BM25 score to fuse)
     * @param filters  the same closed filter set {@code POST /search} accepts
     *                 (may be {@code null})
     * @param scopeCtx the caller's already-resolved scope
     * @param topK     how many hits to return, unpaged (OpenSearch {@code
     *                 size}, {@code from=0})
     * @return up to {@code topK} hits, ordered by {@code bm25Score} descending
     * @throws com.aismartstore.backend.groups.ScopeResolutionException never
     *         thrown here (scope is pre-resolved) - documented on {@code
     *         ScopeContext.resolve} for the caller's own use
     * @throws OpenSearchUnavailableException if OpenSearch itself is
     *         unreachable - never a SQL fallback here (no BM25 equivalent in
     *         Postgres); the caller degrades to {@code semantic_only} instead
     */
    List<LexicalHit> lexicalSearch(String query, SearchFilters filters, ScopeContext scopeCtx, int topK);
}
