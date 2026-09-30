package com.aismartstore.backend.documents.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** {@code POST /documents/trash} body (delete-restore-trash-01-xoa-mem-thung-rac.md §3). */
public record TrashDto(@NotEmpty @Size(max = 500) List<UUID> documentIds, @Size(max = 500) String reason) {
}
