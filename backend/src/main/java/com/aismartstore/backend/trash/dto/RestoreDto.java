package com.aismartstore.backend.trash.dto;

import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * {@code POST /trash/{id}/restore} body (delete-restore-trash-01-xoa-mem-
 * thung-rac.md §3). Every field optional. {@code onNameConflict} is accepted
 * here but only actually consulted once file 02 (delete-restore-trash-02-
 * xoa-cung-xung-dot.md) implements name-conflict detection - this file only
 * covers the no-conflict happy path (§4.3).
 */
public record RestoreDto(
        UUID targetFolderId, @Pattern(regexp = "AUTO_RENAME|CANCEL") String onNameConflict) {

    public RestoreDto() {
        this(null, null);
    }
}
