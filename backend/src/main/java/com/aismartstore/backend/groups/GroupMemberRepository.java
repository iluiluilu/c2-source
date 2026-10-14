package com.aismartstore.backend.groups;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupMemberRepository extends JpaRepository<GroupMember, GroupMemberId> {

    /**
     * Every group id {@code userSub} belongs to - the single query {@link
     * BusinessDataScope} eagerly resolves per call so a DB failure surfaces
     * synchronously as {@link ScopeResolutionException} (Story 4 AC-1), rather
     * than the group lookup happening lazily inside a per-document
     * correlated subquery.
     *
     * <p><b>group-management-sharing-01-group-lifecycle.md Task 6 (rủi ro bảo
     * mật "Cao nhất"):</b> excludes memberships of a soft-deleted group - a
     * theta-join against {@code Group} (there is no JPA association between
     * {@link GroupMember} and {@link Group}) is used instead of a native
     * query so this still runs inside the same {@code Specification}/JPQL
     * world as the rest of this repository.
     */
    @Query("select gm.groupId from GroupMember gm, Group g "
            + "where gm.userSub = :userSub and gm.groupId = g.id and g.deletedAt is null")
    List<UUID> findGroupIdsByUserSub(@Param("userSub") String userSub);

    List<GroupMember> findByGroupIdOrderByJoinedAtAsc(UUID groupId);

    Optional<GroupMember> findByGroupIdAndUserSub(UUID groupId, String userSub);

    boolean existsByGroupIdAndUserSub(UUID groupId, String userSub);

    /** Member count per group for the group list: rows of {groupId, count}. */
    @Query("select gm.groupId, count(gm) from GroupMember gm where gm.groupId in :groupIds group by gm.groupId")
    List<Object[]> countMembersByGroupIds(@Param("groupIds") List<UUID> groupIds);

    /** The caller's own memberships, for "my role" on the group list. */
    List<GroupMember> findByUserSubAndGroupIdIn(String userSub, List<UUID> groupIds);

    /** Story 6 AC-1: current member count of a group, for the 200 members/group cap. */
    long countByGroupId(UUID groupId);

    /** Story 6 AC-2: how many groups a user already belongs to, for the 200 groups/user cap. */
    long countByUserSub(String userSub);

    /** Bất biến "≥ 1 owner" (Story 2/3, sửa Story 4-cũ). */
    long countByGroupIdAndRoleInGroup(UUID groupId, String roleInGroup);

    @Modifying
    int deleteByGroupIdAndUserSub(UUID groupId, String userSub);
}
