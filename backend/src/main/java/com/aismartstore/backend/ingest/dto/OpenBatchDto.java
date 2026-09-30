package com.aismartstore.backend.ingest.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

/** POST /ingest/batches body (data-ingestion.md §3). */
public record OpenBatchDto(
        @NotNull UUID targetFolderId,
        @NotNull @Min(1) Integer declaredFileCount,
        @NotNull @PositiveOrZero Long declaredTotalBytes) {
}
