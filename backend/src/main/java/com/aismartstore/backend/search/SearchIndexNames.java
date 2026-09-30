package com.aismartstore.backend.search;

/**
 * search-fulltext-01-tim-kiem-co-ban.md §2 / Task 3 (ai-search-infra.md QĐ
 * 6/8): every query/bootstrap goes through the <b>alias</b>, never the
 * versioned physical index directly - a future reindex (new analyzer,
 * mapping change) creates {@code segment-v2}/{@code document-v2}, backfills
 * it, then atomically swaps the alias, with zero query-side code change.
 */
public final class SearchIndexNames {

    /** Physical index backing the {@code segment} alias. */
    public static final String SEGMENT_PHYSICAL_INDEX = "segment-v1";
    /** Alias every query/write against segments must use. */
    public static final String SEGMENT_ALIAS = "segment";

    /** Physical index backing the {@code document} alias. */
    public static final String DOCUMENT_PHYSICAL_INDEX = "document-v1";
    /** Alias every query/write against documents must use. */
    public static final String DOCUMENT_ALIAS = "document";

    private SearchIndexNames() {
    }
}
