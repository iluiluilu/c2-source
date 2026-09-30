package com.aismartstore.backend.groups;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A user's membership in a {@link Group} (rbac-group-scope.md §2). {@code
 * roleInGroup} is {@code owner}|{@code member} - deliberately a different
 * axis from the static R1/R2/R3 Keycloak roles in {@link
 * com.aismartstore.backend.auth.Role}.
 */
@Entity
@Table(name = "group_member")
@IdClass(GroupMemberId.class)
public class GroupMember {

    @Id
    @Column(name = "group_id")
    private UUID groupId;

    @Id
    @Column(name = "user_sub")
    private String userSub;

    @Column(name = "role_in_group", nullable = false, length = 16)
    private String roleInGroup;

    @Column(name = "joined_at", nullable = false)
    private OffsetDateTime joinedAt;

    public UUID getGroupId() {
        return groupId;
    }

    public void setGroupId(UUID groupId) {
        this.groupId = groupId;
    }

    public String getUserSub() {
        return userSub;
    }

    public void setUserSub(String userSub) {
        this.userSub = userSub;
    }

    public String getRoleInGroup() {
        return roleInGroup;
    }

    public void setRoleInGroup(String roleInGroup) {
        this.roleInGroup = roleInGroup;
    }

    public OffsetDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(OffsetDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }
}
