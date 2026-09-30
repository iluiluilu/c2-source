package com.aismartstore.backend.personal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * POST /personal/batches/{batchId}/upload-sessions body
 * (chunked-file-upload-02-personal-chunked-upload.md §2.4/§3). No
 * {@code relativePath}/{@code note} - personal storage has no equivalent
 * concept for either (unlike the ingest counterpart, {@code InitUploadSessionDto}).
 */
public record OpenPersonalUploadSessionDto(
        @NotBlank String originalName,
        @Positive long declaredSizeBytes,
        UUID folderId,
        String mimeType) {
}
