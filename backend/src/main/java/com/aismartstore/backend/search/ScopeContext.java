package com.aismartstore.backend.search;

import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.groups.ScopeResolutionException;
import java.util.List;
import java.util.UUID;

/**
 * search-fulltext-01-tim-kiem-co-ban.md §1/Task 4: the OpenSearch-side twin
 * of {@link com.aismartstore.backend.groups.BusinessDataScope} - "phạm vi của
 * tôi" (owner or member of a group the document/segment was shared to) must
 * mean exactly the same thing on both the Postgres and OpenSearch query
 * paths, so both resolve group membership through the very same {@link
 * GroupMemberRepository#findGroupIdsByUserSub(String)} query (rbac-group-scope.md
 * Quyết định 3).
 *
 * @param userSub  the current user's token subject (never blank - {@link
 *                 #resolve} throws before constructing one otherwise)
 * @param groupIds every group id the user is currently a member of, as
 *                 strings (the {@code scope_group_ids} OpenSearch field is
 *                 {@code keyword}) - may be empty, never null
 */
public record ScopeContext(String userSub, List<String> groupIds) {

    /**
     * Fail-closed (search-fulltext-01-tim-kiem-co-ban.md §4 step 1.1): throws
     * {@link ScopeResolutionException} - never falls back to an unscoped or
     * empty-but-silently-permissive context - if {@code sub} is missing or
     * the group membership lookup fails. The caller (`SearchController`) must
     * not call OpenSearch at all when this throws (AC-5).
     */
    public static ScopeContext resolve(String sub, GroupMemberRepository groupMemberRepository) {
        if (sub == null || sub.isBlank()) {
            throw new ScopeResolutionException("Current user token is missing a subject claim");
        }

        List<UUID> groupIds;
        try {
            groupIds = groupMemberRepository.findGroupIdsByUserSub(sub);
        } catch (RuntimeException e) {
            throw new ScopeResolutionException("Failed to resolve the current user's group membership", e);
        }

        return new ScopeContext(sub, groupIds.stream().map(UUID::toString).toList());
    }
}
