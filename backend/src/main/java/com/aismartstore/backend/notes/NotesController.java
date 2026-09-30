package com.aismartstore.backend.notes;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.notes.dto.CreateNoteDto;
import com.aismartstore.backend.notes.dto.UpdateNoteDto;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Java port of the NestJS backend's notes/notes.controller.ts. No DELETE
 * route exists deliberately - add/edit only, so the annotation trail stays
 * intact.
 */
@RestController
@RequestMapping("/documents/{documentId}/notes")
public class NotesController {

    private final NotesService notesService;

    public NotesController(NotesService notesService) {
        this.notesService = notesService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_document.read')")
    public List<DocumentNote> list(@CurrentUser KeycloakUser user, @PathVariable UUID documentId) {
        return notesService.listForDocument(user, documentId);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_note.write')")
    public DocumentNote create(
            @CurrentUser KeycloakUser user, @PathVariable UUID documentId, @Valid @RequestBody CreateNoteDto dto) {
        return notesService.createForDocument(user, documentId, dto.body());
    }

    @PatchMapping("/{noteId}")
    @PreAuthorize("hasAuthority('PERM_note.write')")
    public DocumentNote update(
            @CurrentUser KeycloakUser user,
            @PathVariable UUID documentId,
            @PathVariable UUID noteId,
            @Valid @RequestBody UpdateNoteDto dto) {
        return notesService.update(user, documentId, noteId, dto);
    }
}
