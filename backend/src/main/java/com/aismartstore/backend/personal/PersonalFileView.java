package com.aismartstore.backend.personal;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * GET /personal/files row: the {@link PersonalFile} fields exactly as before
 * (same JSON names) plus {@code addedToWarehouse} (personal-to-warehouse.md AC-21).
 */
public record PersonalFileView(
        UUID id,
        String ownerSub,
        String ownerUsername,
        UUID folderId,
        String originalName,
        String displayName,
        String relativePath,
        String mimeType,
        long size,
        String sha256,
        UUID batchId,
        OffsetDateTime createdAt,
        boolean addedToWarehouse) {

    public static PersonalFileView of(PersonalFile f, boolean addedToWarehouse) {
        return new PersonalFileView(
                f.getId(), f.getOwnerSub(), f.getOwnerUsername(), f.getFolderId(), f.getOriginalName(),
                f.getDisplayName(), f.getRelativePath(), f.getMimeType(), f.getSize(), f.getSha256(), f.getBatchId(), f.getCreatedAt(),
                addedToWarehouse);
    }
}
