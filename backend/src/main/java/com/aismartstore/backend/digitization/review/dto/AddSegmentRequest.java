package com.aismartstore.backend.digitization.review.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * digitization-review-01-ocr.md §3 (US2 AC-5): {@code POST
 * /digitization-review/runs/{runId}/segments} request body - adds a manual
 * segment, {@code origin='manual'}.
 */
public record AddSegmentRequest(Integer afterSegmentIndex, @NotBlank String text) {
}
