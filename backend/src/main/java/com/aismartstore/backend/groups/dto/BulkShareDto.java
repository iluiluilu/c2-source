package com.aismartstore.backend.groups.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** POST /documents/shares request body (group-management-sharing-02-document-sharing.md §3.1). */
public record BulkShareDto(@NotNull UUID groupId, @NotEmpty @Size(max = 100) List<UUID> documentIds) {
}
