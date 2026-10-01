package com.aismartstore.backend.personal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * POST /personal/batches/{batchId}/upload-sessions body
 * (chunked-file-upload-02-personal-chunked-upload.md §2.4/§3). No
 * {@code note} - personal storage has no equivalent concept. SPEC-folder-upload-display-name
 * adds {@code displayName} + {@code relativePath} (folder-drop keeps its sub-path).
 */
public record OpenPersonalUploadSessionDto(
        @NotBlank String originalName,
        String displayName,
        String relativePath,
        @Positive long declaredSizeBytes,
        UUID folderId,
        String mimeType) {
}
