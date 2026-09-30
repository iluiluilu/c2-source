package com.aismartstore.backend.scanning.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

/** POST /admin/allowed-file-formats body (data-ingestion.md §3, Story 5 AC-3). */
public record CreateAllowedFileFormatDto(
        @NotBlank String mimeType,
        @NotEmpty List<@NotBlank String> extensions,
        @NotBlank String category,
        List<Map<String, Object>> magicBytes) {
}
