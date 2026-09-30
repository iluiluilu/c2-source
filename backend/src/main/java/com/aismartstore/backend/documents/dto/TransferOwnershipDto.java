package com.aismartstore.backend.documents.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /admin/document-ownership/{documentId}/transfer} body
 * (digitization-review-03-ownership-transfer.md §3.2/§7). {@code toUserSub}
 * is the Keycloak {@code sub} of B, not username/email (§3.2). {@code
 * reason} is optional (rủi ro mục 7, DEV quyết định + xác nhận 2026-09-08),
 * capped at 500 chars.
 */
public record TransferOwnershipDto(@NotBlank String toUserSub, @Size(max = 500) String reason) {
}
