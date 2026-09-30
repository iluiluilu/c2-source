package com.aismartstore.backend.notes.dto;

import com.aismartstore.backend.notes.NoteConstraints;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateNoteDto(@NotBlank @Size(max = NoteConstraints.NOTE_MAX_LENGTH) String body) {
}
