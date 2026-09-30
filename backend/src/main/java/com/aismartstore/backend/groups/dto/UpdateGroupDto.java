package com.aismartstore.backend.groups.dto;

import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /groups/{id}} (Story 1). Both fields optional: {@code null}
 * means "leave unchanged" - matches {@code UpdateAccountDto}'s convention.
 */
public record UpdateGroupDto(@Size(max = 255) String name, @Size(max = 2000) String description) {
}
