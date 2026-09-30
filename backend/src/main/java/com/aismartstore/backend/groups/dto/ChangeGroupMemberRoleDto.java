package com.aismartstore.backend.groups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** {@code PATCH /groups/{id}/members/{userSub}} (Story 2). */
public record ChangeGroupMemberRoleDto(@NotBlank @Pattern(regexp = "owner|member") String roleInGroup) {
}
