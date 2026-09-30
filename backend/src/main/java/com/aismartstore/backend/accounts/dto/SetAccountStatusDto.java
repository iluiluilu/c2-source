package com.aismartstore.backend.accounts.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code PATCH /accounts/{id}/status} body (Story 3/4). Replaces the old
 * {@code {enabled: boolean}} contract. {@code status} must be one of
 * active/locked/disabled; {@code reason} is required for every transition
 * (including reactivating/unlocking, for audit traceability) - both rules
 * are cross-field/value-set checks validated in
 * {@code AccountsService.setStatus()} rather than here, so the error
 * messages can match this project's existing Vietnamese copy.
 */
public record SetAccountStatusDto(@NotBlank String status, String reason) {
}
