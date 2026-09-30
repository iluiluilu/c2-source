package com.aismartstore.backend.groups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateGroupDto(
        @NotBlank @Size(max = 255) String name, @Size(max = 2000) String description) {
}
