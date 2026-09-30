package com.aismartstore.backend.search.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * search-fulltext-02-tim-kiem-nang-cao.md §2 - the {@code filters} object of
 * {@code POST /search}, one field per closed group (AC-1-AC-6). Every field is
 * optional; {@link #isEmpty()} is what {@code SearchService} uses to decide
 * whether "query rỗng và filters rỗng hoàn toàn" applies (§3.3).
 *
 * @param docType        AC-1 - exactly one value, validated against the 4
 *                        fixed {@code doc_type} tag labels
 *                        (classification-notes-01-tags.md V20 seed), not an
 *                        array.
 * @param topicTags      AC-2 - up to 20 (QĐ 3 SA/AC-13) {@code topic} tag
 *                        labels.
 * @param topicTagsMode  AC-2 - {@code "AND"} (default) or {@code "OR"} between
 *                        the selected {@link #topicTags()}; any other/blank
 *                        value silently falls back to {@code "AND"} (only
 *                        size limits are validated per §3.3's table, not this
 *                        field).
 * @param uploadedFrom   AC-3 - inclusive lower bound on {@code uploaded_at}
 *                        ("Ngày nạp kho", not the document's issue date).
 * @param uploadedTo     AC-3 - inclusive upper bound (end of day) on
 *                        {@code uploaded_at}.
 * @param groupIds       AC-4 - up to 20 group ids; narrowed to
 *                        {@code ∩ ScopeContext.groupIds()} before querying
 *                        (§4 step 1.2 "group_ids") - ids outside the caller's
 *                        own scope are dropped silently, never rejected.
 * @param folderId       AC-5 - filters the folder and its whole subtree (up to
 *                        5 levels, warehouse-structure.md) via
 *                        {@code folder_path_ids}, never a string-prefix match.
 * @param mediaType      AC-6 - up to 20 values from {@link
 *                        com.aismartstore.backend.search.MediaTypeResolver}'s
 *                        3 categories.
 * @param confirmSource  AC-6 - {@code "manual"} or {@code "auto"}, optional.
 */
public record SearchFilters(
        String docType,
        List<String> topicTags,
        String topicTagsMode,
        LocalDate uploadedFrom,
        LocalDate uploadedTo,
        List<UUID> groupIds,
        UUID folderId,
        List<String> mediaType,
        String confirmSource) {

    public static final String TOPIC_TAGS_MODE_AND = "AND";
    public static final String TOPIC_TAGS_MODE_OR = "OR";

    /** §3.3: "query rỗng và filters rỗng hoàn toàn" - every group below is unset/empty. */
    public boolean isEmpty() {
        return (docType == null || docType.isBlank())
                && (topicTags == null || topicTags.isEmpty())
                && uploadedFrom == null
                && uploadedTo == null
                && (groupIds == null || groupIds.isEmpty())
                && folderId == null
                && (mediaType == null || mediaType.isEmpty())
                && (confirmSource == null || confirmSource.isBlank());
    }

    /** AC-2: only {@code "OR"} (case-insensitive) switches mode; everything else (including null/blank) is AND. */
    public String resolvedTopicTagsMode() {
        return TOPIC_TAGS_MODE_OR.equalsIgnoreCase(topicTagsMode) ? TOPIC_TAGS_MODE_OR : TOPIC_TAGS_MODE_AND;
    }
}
