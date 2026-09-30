package com.aismartstore.backend.search.dto;

/**
 * {@code POST /search} body (search-fulltext-01-tim-kiem-co-ban.md §3.1,
 * extended by search-fulltext-02-tim-kiem-nang-cao.md §3.1 with {@link
 * #filters()}). Neither {@code query} nor {@code filters} is mandatory on its
 * own any more - {@code SearchService} rejects the request only if
 * <b>both</b> are empty (§3.3 AC-7 story {@code -01}); every other limit
 * (length/token count/per-filter max size, §3.3 AC-13 story {@code -02}) is
 * validated there too, not via Bean Validation annotations, since the exact
 * wording/token-count check needs more than a declarative constraint can
 * express. {@code sort}/{@code page}/{@code size} are all optional; defaults/
 * limits are resolved by {@code SearchService} against {@code system-config}
 * (Task 8), not here, since the size ceiling is a fixed code constant while
 * the default page size is admin-tunable.
 */
public record SearchRequest(String query, SearchFilters filters, String sort, Integer page, Integer size) {
}
