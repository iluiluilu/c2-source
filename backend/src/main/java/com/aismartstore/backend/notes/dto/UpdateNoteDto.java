package com.aismartstore.backend.notes.dto;

import com.aismartstore.backend.notes.NoteConstraints;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** {@code expectedUpdatedAt} is the optimistic-concurrency token (omit to skip the check). */
public record UpdateNoteDto(
        @NotBlank @Size(max = NoteConstraints.NOTE_MAX_LENGTH) String body, OffsetDateTime expectedUpdatedAt) {
}
