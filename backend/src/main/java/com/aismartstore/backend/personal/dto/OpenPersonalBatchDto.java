package com.aismartstore.backend.personal.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

/**
 * POST /personal/batches body (personal-storage-quota.md §3). Unlike
 * {@code data-ingestion.md}'s {@code OpenBatchDto}, {@code folderId} is
 * optional - omitted/null means the personal storage root.
 */
public record OpenPersonalBatchDto(
        UUID folderId,
        @NotNull @Min(1) Integer declaredFileCount,
        @NotNull @PositiveOrZero Long declaredTotalBytes) {
}
