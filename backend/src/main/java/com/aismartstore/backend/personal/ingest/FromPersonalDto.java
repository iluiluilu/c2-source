package com.aismartstore.backend.personal.ingest;

import com.aismartstore.backend.notes.NoteConstraints;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** POST /ingest/from-personal body (personal-to-warehouse.md §3). At least one of the two lists must be non-empty. */
public record FromPersonalDto(
        @NotNull UUID targetFolderId,
        @Size(max = NoteConstraints.NOTE_MAX_LENGTH) String note,
        List<UUID> fileIds,
        List<UUID> folderIds) {

    public List<UUID> fileIdsOrEmpty() {
        return fileIds == null ? List.of() : fileIds;
    }

    public List<UUID> folderIdsOrEmpty() {
        return folderIds == null ? List.of() : folderIds;
    }
}
