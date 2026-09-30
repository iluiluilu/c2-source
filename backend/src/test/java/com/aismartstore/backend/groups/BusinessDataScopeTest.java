package com.aismartstore.backend.groups;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/**
 * Unit coverage for rbac-group-scope.md §5 ("BusinessDataScope throw
 * ScopeResolutionException khi groupMemberRepository ném lỗi") - Story 4
 * AC-1. The "generates the right SQL for the 3 visibility cases" half of that
 * bullet needs a real persistence context to exercise the {@link
 * org.springframework.data.jpa.domain.Specification}'s subquery against
 * actual rows, so it lives in {@link BusinessDataScopeIT} instead (a plain
 * Mockito test cannot evaluate a JPA Criteria {@code Predicate} without a
 * real {@code EntityManager}).
 */
class BusinessDataScopeTest {

    @Test
    void throwsScopeResolutionExceptionWhenCurrentUserSubjectIsMissing() {
        GroupMemberRepository repo = mock(GroupMemberRepository.class);

        assertThatThrownBy(() -> BusinessDataScope.forDocuments(null, repo))
                .isInstanceOf(ScopeResolutionException.class);
        assertThatThrownBy(() -> BusinessDataScope.forDocuments("  ", repo))
                .isInstanceOf(ScopeResolutionException.class);
    }

    @Test
    void throwsScopeResolutionExceptionWhenGroupMembershipLookupFails() {
        GroupMemberRepository repo = mock(GroupMemberRepository.class);
        when(repo.findGroupIdsByUserSub("user-1")).thenThrow(new QueryTimeoutException("DB unavailable"));

        assertThatThrownBy(() -> BusinessDataScope.forDocuments("user-1", repo))
                .isInstanceOf(ScopeResolutionException.class)
                .hasCauseInstanceOf(QueryTimeoutException.class);
    }

    @Test
    void doesNotThrowWhenGroupMembershipLookupSucceeds() {
        GroupMemberRepository repo = mock(GroupMemberRepository.class);
        when(repo.findGroupIdsByUserSub("user-1")).thenReturn(List.of(UUID.randomUUID()));

        BusinessDataScope.forDocuments("user-1", repo); // must not throw
    }
}
