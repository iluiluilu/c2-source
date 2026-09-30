package com.aismartstore.backend.search;

import com.aismartstore.backend.search.dto.SearchFilters;
import java.util.ArrayList;
import java.util.List;

/**
 * search-fulltext-02-tim-kiem-nang-cao.md §4 step 2: the fixed set of
 * removable filter groups used by both {@link SearchQueryBuilder}'s
 * {@code exclude} parameter and the AC-12 "0 kết quả" diagnostics - {@code
 * folderId}/{@code mediaType}/{@code confirmSource} are diagnosed (and
 * removed) together as a single group, everything else independently.
 */
public enum FilterGroup {
    DOC_TYPE("docType"),
    TOPIC_TAGS("topicTags"),
    UPLOADED_AT("uploadedFrom/uploadedTo"),
    GROUP_IDS("groupIds"),
    FOLDER_MEDIA_CONFIRM("folderId/mediaType/confirmSource");

    private final String label;

    FilterGroup(String label) {
        this.label = label;
    }

    /** The exact {@code removedFilter} string in {@code RefineSuggestion} (§3.2 example). */
    public String label() {
        return label;
    }

    /**
     * Every group that currently contributes at least one filter clause, in
     * the fixed order §4 step 2 chốt (DEV, not a business decision) - the
     * order the diagnostic {@code _msearch} queries are issued in.
     *
     * @param effectiveGroupIds already-intersected with {@link
     *                          ScopeContext#groupIds()} (§4 step 1.2) - an
     *                          empty result here means "groupIds" contributes
     *                          no filter clause at all, so it is not active.
     */
    public static List<FilterGroup> activeGroupsOf(SearchFilters filters, List<String> effectiveGroupIds) {
        List<FilterGroup> active = new ArrayList<>();
        if (filters == null) {
            return active;
        }
        if (filters.docType() != null && !filters.docType().isBlank()) {
            active.add(DOC_TYPE);
        }
        if (filters.topicTags() != null && !filters.topicTags().isEmpty()) {
            active.add(TOPIC_TAGS);
        }
        if (filters.uploadedFrom() != null || filters.uploadedTo() != null) {
            active.add(UPLOADED_AT);
        }
        if (effectiveGroupIds != null && !effectiveGroupIds.isEmpty()) {
            active.add(GROUP_IDS);
        }
        boolean folderMediaConfirm = filters.folderId() != null
                || (filters.mediaType() != null && !filters.mediaType().isEmpty())
                || (filters.confirmSource() != null && !filters.confirmSource().isBlank());
        if (folderMediaConfirm) {
            active.add(FOLDER_MEDIA_CONFIRM);
        }
        return active;
    }
}
