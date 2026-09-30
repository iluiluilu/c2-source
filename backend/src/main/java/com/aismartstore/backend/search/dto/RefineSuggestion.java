package com.aismartstore.backend.search.dto;

/**
 * search-fulltext-02-tim-kiem-nang-cao.md §3.2/§4 step 2 (AC-12): one "bỏ bộ
 * lọc X sẽ có N kết quả" suggestion, computed by re-running the search with
 * exactly one {@link SearchFilters} group removed (never {@code query}
 * itself - dropping the keyword is not offered as a suggestion).
 *
 * @param removedFilter one of {@code "docType"}, {@code "topicTags"},
 *                       {@code "uploadedFrom/uploadedTo"}, {@code "groupIds"},
 *                       {@code "folderId/mediaType/confirmSource"} (the fixed
 *                       diagnostic groups DEV chốt at §4 step 2 - the last 3
 *                       are diagnosed together as one group).
 * @param estimatedCount matched document count with that one group removed
 *                       (always {@code > 0} - zero-count suggestions are
 *                       filtered out before this DTO is built).
 */
public record RefineSuggestion(String removedFilter, long estimatedCount) {
}
