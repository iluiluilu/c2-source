package com.aismartstore.backend.search;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * search-fulltext-02-tim-kiem-nang-cao.md §4 step 1.2 (AC-4): {@code
 * filters.groupIds() ∩ ctx.groupIds()}. A requested group id the caller is
 * not actually a member of is dropped silently (never a validation error,
 * never a leaked "this group doesn't exist" signal) - {@link #dropped()} only
 * feeds the {@code search.executed} audit entry (Task 3).
 *
 * @param effective ids to actually filter on (both the OpenSearch {@code
 *                  scope_group_ids} clause and the Postgres fallback), as
 *                  strings to match {@code ScopeContext.groupIds()}'s shape
 * @param dropped   requested ids outside the caller's own scope, as strings
 */
public record GroupIdIntersection(List<String> effective, List<String> dropped) {

    private static final GroupIdIntersection EMPTY = new GroupIdIntersection(List.of(), List.of());

    public static GroupIdIntersection resolve(List<UUID> requestedGroupIds, ScopeContext ctx) {
        if (requestedGroupIds == null || requestedGroupIds.isEmpty()) {
            return EMPTY;
        }

        Set<String> allowed = new HashSet<>(ctx.groupIds());
        List<String> effective = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (UUID requested : requestedGroupIds) {
            String value = requested.toString();
            if (allowed.contains(value)) {
                effective.add(value);
            } else {
                dropped.add(value);
            }
        }
        // §4 step 1.2: an empty intersection is NOT treated as "no results" - the base
        // ScopeFilterBuilder scope already applies, this group simply contributes nothing extra.
        return new GroupIdIntersection(effective, dropped);
    }
}
