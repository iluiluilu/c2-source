package com.aismartstore.backend.tags.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** classification-notes-01-tags.md §3: {@code PATCH /tags/{id}} - đổi tên `topic` (US3 AC-2). */
public record RenameTagDto(@NotBlank @Size(max = 128) String label) {
}
