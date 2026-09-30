package com.aismartstore.backend.groups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AddGroupMemberDto(
        @NotBlank String userSub, @NotBlank @Pattern(regexp = "owner|member") String roleInGroup) {
}
