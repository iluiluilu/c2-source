package com.aismartstore.backend.warehouse.dto;

import java.util.UUID;

/** {@code newParentId == null} means "move to root" (spec §3, PATCH /folders/{id}/move). */
public record MoveFolderDto(UUID newParentId) {
}
