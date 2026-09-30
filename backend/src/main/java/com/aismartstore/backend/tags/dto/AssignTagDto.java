package com.aismartstore.backend.tags.dto;

import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * {@code POST /documents/{documentId}/tags}. Exactly one of {@code tagId}
 * (assign an existing tag) or {@code label} (classification-notes-01-tags.md
 * §4.2 - ad-hoc {@code topic} creation, US1 AC-3/US3, gated by
 * {@code classification.allow_adhoc_topic_tag}) must be present -
 * {@code TagsService.assign} validates that combination since
 * {@code @NotNull}/bean validation can't express "exactly one of" cleanly.
 */
public record AssignTagDto(UUID tagId, @Size(max = 128) String label) {
}
