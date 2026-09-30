package com.aismartstore.backend.digitization.review.dto;

import jakarta.validation.constraints.NotNull;

/**
 * digitization-review-01-ocr.md §3: {@code PATCH
 * /digitization-review/segments/{segmentId}} request body - shared by both
 * OCR (this spec, only {@code text}) and STT (digitization-review-02-stt.md
 * §3, adds {@code timeStartMs}/{@code timeEndMs}). {@code text}/{@code
 * timeStartMs}/{@code timeEndMs} are all optional here at the transport
 * layer; a run whose {@code task='ocr'} simply never receives the time
 * fields from the OCR review UI - no separate DTO per task.
 */
public record PatchSegmentRequest(
        @NotNull Integer expectedRevisionNo, String text, String reason, Integer timeStartMs, Integer timeEndMs) {
}
