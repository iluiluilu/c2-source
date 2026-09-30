package com.aismartstore.backend.notes;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ConflictException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.groups.DocumentScopeGuard;
import com.aismartstore.backend.notes.dto.UpdateNoteDto;
import com.aismartstore.backend.outbox.OutboxEvent;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Java port of the NestJS backend's notes/notes.service.ts (FR-16), extended
 * per classification-notes-02-notes.md: {@link DocumentScopeGuard} on every
 * document-scoped read/write (§4 step 2/3, closing the gap where {@code
 * createForDocument}/{@code update} only checked {@code
 * documentRepository.existsById} instead of the caller's scope), and the
 * {@code outbox_event} write on every note create/update (§4.6/§2.2 - table
 * owned by this spec, shared with {@code document.classification.changed}).
 *
 * <p>rbac-group-scope.md Task 8/Story 3 AC-1-AC-3: {@link #listForDocument}
 * goes through {@link DocumentScopeGuard} instead of {@code
 * DocumentsService.findOne} to avoid a dependency cycle - {@code
 * DocumentsService} already depends on {@code NotesService} for the
 * upload-time note.
 */
@Service
public class NotesService {

    /** classification-notes-02-notes.md §2.2: payload contract, no {@code body}. */
    private static final String OUTBOX_EVENT_TYPE = "document.note.changed";

    private final DocumentNoteRepository noteRepository;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;
    private final DocumentScopeGuard documentScopeGuard;
    private final OutboxEventRepository outboxEventRepository;

    public NotesService(
            DocumentNoteRepository noteRepository,
            AuditService auditService,
            AuditRequestContext auditRequestContext,
            DocumentScopeGuard documentScopeGuard,
            OutboxEventRepository outboxEventRepository) {
        this.noteRepository = noteRepository;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
        this.documentScopeGuard = documentScopeGuard;
        this.outboxEventRepository = outboxEventRepository;
    }

    /**
     * classification-notes-02-notes.md §4 step 2 (Task 1/2/3): {@link
     * DocumentScopeGuard#requireVisible} replaces the old {@code
     * documentRepository.existsById} check (same 404-for-out-of-scope
     * contract, auto-audits {@code access.denied}); {@code document_note}
     * insert and the {@code outbox_event} row are written atomically in one
     * {@code @Transactional} method.
     */
    @Transactional
    public DocumentNote createForDocument(KeycloakUser user, UUID documentId, String body) {
        documentScopeGuard.requireVisible(user, documentId);

        DocumentNote note = new DocumentNote();
        note.setDocumentId(documentId);
        note.setBody(body);
        note.setCreatedBySub(user.sub());
        note.setCreatedByUsername(user.preferredUsername());
        note.setUpdatedBySub(null);
        note.setUpdatedByUsername(null);
        OffsetDateTime now = OffsetDateTime.now();
        note.setCreatedAt(now);
        note.setUpdatedAt(now);
        noteRepository.save(note);

        writeOutboxEvent(documentId, user.sub());

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "note.create", "document_note", note.getId().toString(),
                "success", auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                Map.of("documentId", documentId.toString(), "noteId", note.getId().toString())));
        return note;
    }

    /** rbac-group-scope.md Task 8/Story 3 AC-1-AC-3: notes belong to exactly one document, scoped through it. */
    public List<DocumentNote> listForDocument(KeycloakUser user, UUID documentId) {
        documentScopeGuard.requireVisible(user, documentId);
        return noteRepository.findByDocumentIdOrderByCreatedAtDesc(documentId);
    }

    /**
     * classification-notes-02-notes.md §4 step 3 (Task 1/2/3): {@code
     * requireVisible} runs before the {@code noteId} lookup so a 404 never
     * distinguishes "document out of scope" from "note not found"; the
     * {@code document_note} update and the {@code outbox_event} row are
     * written atomically in one {@code @Transactional} method.
     */
    @Transactional
    public DocumentNote update(KeycloakUser user, UUID documentId, UUID noteId, UpdateNoteDto dto) {
        documentScopeGuard.requireVisible(user, documentId);

        DocumentNote note = noteRepository
                .findByIdAndDocumentId(noteId, documentId)
                .orElseThrow(() -> new NotFoundException("Note not found"));

        if (dto.expectedUpdatedAt() != null && !dto.expectedUpdatedAt().isEqual(note.getUpdatedAt())) {
            throw new ConflictException("Ghi chú đã được người khác sửa đổi; tải lại trước khi lưu");
        }

        note.setBody(dto.body());
        note.setUpdatedBySub(user.sub());
        note.setUpdatedByUsername(user.preferredUsername());
        note.setUpdatedAt(OffsetDateTime.now());
        noteRepository.save(note);

        writeOutboxEvent(documentId, user.sub());

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "note.update", "document_note", note.getId().toString(),
                "success", auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                Map.of("documentId", documentId.toString(), "noteId", note.getId().toString())));
        return note;
    }

    /**
     * classification-notes-02-notes.md §2.2: one {@code outbox_event} row per
     * successful create/update, in the same transaction as the {@code
     * document_note} write (atomic at the DB layer - not a retry/queue for
     * the downstream consumer, see {@code OutboxRetentionJob}'s Javadoc).
     * Payload is exactly {@code {documentId, changedAt, changedBySub}} - no
     * {@code body} (an indexer re-reads {@code notes[]} from the DB by
     * {@code documentId} when it consumes this event).
     */
    private void writeOutboxEvent(UUID documentId, String changedBySub) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType(OUTBOX_EVENT_TYPE);
        OffsetDateTime changedAt = OffsetDateTime.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", documentId.toString());
        payload.put("changedAt", changedAt.toString());
        payload.put("changedBySub", changedBySub);
        event.setPayload(payload);
        event.setCreatedAt(changedAt);
        outboxEventRepository.save(event);
    }
}
