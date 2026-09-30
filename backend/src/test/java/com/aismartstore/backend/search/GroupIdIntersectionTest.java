package com.aismartstore.backend.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** search-fulltext-02-tim-kiem-nang-cao.md §4 step 1.2/§5 (AC-4) - pure unit coverage, no OpenSearch/DB involved. */
class GroupIdIntersectionTest {

    @Test
    void keepsOnlyIdsTheCallerIsAMemberOfAndReportsTheRestAsDropped() {
        UUID memberGroupId = UUID.randomUUID();
        UUID foreignGroupId = UUID.randomUUID();
        ScopeContext ctx = new ScopeContext("user-1", List.of(memberGroupId.toString()));

        GroupIdIntersection result = GroupIdIntersection.resolve(List.of(memberGroupId, foreignGroupId), ctx);

        assertThat(result.effective()).containsExactly(memberGroupId.toString());
        assertThat(result.dropped()).containsExactly(foreignGroupId.toString());
    }

    @Test
    void returnsEmptyEffectiveAndEmptyDroppedForANullOrEmptyRequest() {
        ScopeContext ctx = new ScopeContext("user-1", List.of(UUID.randomUUID().toString()));

        assertThat(GroupIdIntersection.resolve(null, ctx).effective()).isEmpty();
        assertThat(GroupIdIntersection.resolve(null, ctx).dropped()).isEmpty();
        assertThat(GroupIdIntersection.resolve(List.of(), ctx).effective()).isEmpty();
    }

    @Test
    void anEmptyIntersectionIsNotAnErrorWhenTheCallerRequestedOnlyForeignGroups() {
        ScopeContext ctx = new ScopeContext("user-1", List.of());
        UUID foreignGroupId = UUID.randomUUID();

        GroupIdIntersection result = GroupIdIntersection.resolve(List.of(foreignGroupId), ctx);

        assertThat(result.effective()).isEmpty();
        assertThat(result.dropped()).containsExactly(foreignGroupId.toString());
    }
}
