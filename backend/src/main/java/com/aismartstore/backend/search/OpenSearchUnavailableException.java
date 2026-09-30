package com.aismartstore.backend.search;

/**
 * search-fulltext-01-tim-kiem-co-ban.md §4 step 2 (AC-11): OpenSearch itself
 * is unreachable (e.g. {@code ConnectException} - the service is down),
 * as opposed to a single slow query timing out. {@code SearchService} maps
 * this to {@code degradedMode = "opensearch_unavailable"}, HTTP 200 with
 * {@code results = []} - never a 5xx, and never a SQL fallback for a
 * keyword search (no equivalent Vietnamese full-text exists in Postgres).
 */
public class OpenSearchUnavailableException extends RuntimeException {

    public OpenSearchUnavailableException(Throwable cause) {
        super("OpenSearch is unavailable", cause);
    }
}
