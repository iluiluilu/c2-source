package com.aismartstore.backend.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.groups.ScopeResolutionException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/**
 * search-fulltext-01-tim-kiem-co-ban.md §5 - mirrors
 * {@code BusinessDataScopeTest} (rbac-group-scope.md Story 4 AC-1): {@link
 * ScopeContext#resolve} must fail closed exactly the same way the Postgres
 * side does, since both must agree on "phạm vi của tôi".
 */
class ScopeContextTest {

    @Test
    void throwsScopeResolutionExceptionWhenSubjectIsMissing() {
        GroupMemberRepository repo = mock(GroupMemberRepository.class);

        assertThatThrownBy(() -> ScopeContext.resolve(null, repo)).isInstanceOf(ScopeResolutionException.class);
        assertThatThrownBy(() -> ScopeContext.resolve("  ", repo)).isInstanceOf(ScopeResolutionException.class);
    }

    @Test
    void throwsScopeResolutionExceptionWhenGroupMembershipLookupFails() {
        GroupMemberRepository repo = mock(GroupMemberRepository.class);
        when(repo.findGroupIdsByUserSub("user-1")).thenThrow(new QueryTimeoutException("DB unavailable"));

        assertThatThrownBy(() -> ScopeContext.resolve("user-1", repo))
                .isInstanceOf(ScopeResolutionException.class)
                .hasCauseInstanceOf(QueryTimeoutException.class);
    }

    @Test
    void resolvesGroupIdsAsStrings() {
        GroupMemberRepository repo = mock(GroupMemberRepository.class);
        UUID groupA = UUID.randomUUID();
        UUID groupB = UUID.randomUUID();
        when(repo.findGroupIdsByUserSub("user-1")).thenReturn(List.of(groupA, groupB));

        ScopeContext ctx = ScopeContext.resolve("user-1", repo);

        assertThat(ctx.userSub()).isEqualTo("user-1");
        assertThat(ctx.groupIds()).containsExactlyInAnyOrder(groupA.toString(), groupB.toString());
    }

    @Test
    void resolvesToAnEmptyGroupListWhenTheUserHasNoGroups() {
        GroupMemberRepository repo = mock(GroupMemberRepository.class);
        when(repo.findGroupIdsByUserSub("user-1")).thenReturn(List.of());

        ScopeContext ctx = ScopeContext.resolve("user-1", repo);

        assertThat(ctx.groupIds()).isEmpty();
    }
}
