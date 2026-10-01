package com.aismartstore.backend.ingest.dto;

import com.aismartstore.backend.notes.NoteConstraints;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * POST /ingest/batches/{batchId}/upload-sessions body
 * (chunked-file-upload-01-ingest-chunked-upload.md §3/§4.1).
 *
 * <p>{@code note} is not spelled out in the spec's API contract table (§3),
 * but §2.1 explicitly adds {@code upload_session.note} to carry the same
 * per-file note the whole-file flow (`POST .../files`) accepts as a form
 * field through to {@code complete()} - so it is accepted here too, filling
 * that gap rather than leaving the column permanently unset for every
 * chunked upload.
 */
public record InitUploadSessionDto(
        @NotBlank @Size(max = 4096) String originalName,
        @Size(max = 4096) String relativePath,
        @Size(max = 4096) String displayName,
        @NotNull @Min(1) Long declaredSizeBytes,
        String mimeType,
        @Size(max = NoteConstraints.NOTE_MAX_LENGTH) String note) {
}
