package com.aismartstore.backend.accounts.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** {@code PATCH /accounts/{id}/quota} body (Story 5). */
public record SetAccountQuotaDto(@NotNull @Min(1) Long storageQuotaBytes) {
}
