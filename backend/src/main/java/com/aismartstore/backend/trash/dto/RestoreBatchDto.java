package com.aismartstore.backend.trash.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** {@code POST /trash/restore-batch} body (delete-restore-trash-01-xoa-mem-thung-rac.md §3). */
public record RestoreBatchDto(
        @NotEmpty @Size(max = 500) List<UUID> documentIds,
        UUID targetFolderId,
        @Pattern(regexp = "AUTO_RENAME|CANCEL") String onNameConflict) {
}
