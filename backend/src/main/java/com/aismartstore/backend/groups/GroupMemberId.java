package com.aismartstore.backend.groups;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key for {@link GroupMember} (group_id, user_sub). */
public class GroupMemberId implements Serializable {

    private UUID groupId;
    private String userSub;

    public GroupMemberId() {
    }

    public GroupMemberId(UUID groupId, String userSub) {
        this.groupId = groupId;
        this.userSub = userSub;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GroupMemberId that)) {
            return false;
        }
        return Objects.equals(groupId, that.groupId) && Objects.equals(userSub, that.userSub);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, userSub);
    }
}
