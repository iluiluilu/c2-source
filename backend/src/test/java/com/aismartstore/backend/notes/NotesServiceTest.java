package com.aismartstore.backend.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit coverage of classification-notes-02-notes.md §5 ("Unit test -
 * NotesServiceTest"). Every collaborator is mocked - real DB-level
 * atomicity of the {@code document_note}/{@code outbox_event} write and the
 * real {@link DocumentScopeGuard} {@code access.denied} audit trail are
 * covered by {@code NotesControllerIT}/{@code NotesOutboxAtomicityIT}
 * instead.
 */
class NotesServiceTest {

    private DocumentNoteRepository noteRepository;
    private AuditService auditService;
    private DocumentScopeGuard documentScopeGuard;
    private OutboxEventRepository outboxEventRepository;
    private NotesService notesService;

    private final KeycloakUser user = new KeycloakUser("user-1", "user-1", List.of("can-bo"));
    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        noteRepository = mock(DocumentNoteRepository.class);
        auditService = mock(AuditService.class);
        AuditRequestContext auditRequestContext = mock(AuditRequestContext.class);
        documentScopeGuard = mock(DocumentScopeGuard.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        notesService = new NotesService(
                noteRepository, auditService, auditRequestContext, documentScopeGuard, outboxEventRepository);

        when(noteRepository.save(any())).thenAnswer(invocation -> {
            DocumentNote note = invocation.getArgument(0);
            if (note.getId() == null) {
                setId(note, UUID.randomUUID());
            }
            return note;
        });
    }

    /** {@code DocumentNote.id} has no public setter (DB-generated) - reflection keeps fixtures realistic. */
    private static void setId(DocumentNote note, UUID id) {
        try {
            var field = DocumentNote.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(note, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static DocumentNote existingNote(UUID documentId, UUID noteId, OffsetDateTime updatedAt) {
        DocumentNote note = new DocumentNote();
        note.setDocumentId(documentId);
        note.setBody("cũ");
        note.setCreatedBySub("someone");
        note.setCreatedByUsername("someone");
        note.setCreatedAt(updatedAt);
        note.setUpdatedAt(updatedAt);
        setId(note, noteId);
        return note;
    }

    // ---- Task 1: requireVisible ở đầu createForDocument/update -----------------

    @Test
    void createForDocumentThrowsNotFoundWhenOutOfScope() {
        doThrow(new NotFoundException("Document not found"))
                .when(documentScopeGuard)
                .requireVisible(user, documentId);

        assertThatThrownBy(() -> notesService.createForDocument(user, documentId, "nội dung"))
                .isInstanceOf(NotFoundException.class);

        verify(noteRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void createForDocumentSucceedsWhenInScope() {
        DocumentNote created = notesService.createForDocument(user, documentId, "nội dung");

        verify(documentScopeGuard).requireVisible(user, documentId);
        assertThat(created.getBody()).isEqualTo("nội dung");
        assertThat(created.getCreatedBySub()).isEqualTo(user.sub());
        verify(noteRepository).save(any());
    }

    @Test
    void updateChecksScopeBeforeLookingUpTheNoteId() {
        UUID noteId = UUID.randomUUID();
        doThrow(new NotFoundException("Document not found"))
                .when(documentScopeGuard)
                .requireVisible(user, documentId);

        assertThatThrownBy(() -> notesService.update(user, documentId, noteId, new UpdateNoteDto("mới", null)))
                .isInstanceOf(NotFoundException.class);

        verify(noteRepository, never()).findByIdAndDocumentId(any(), any());
    }

    @Test
    void updateThrowsNotFoundWhenOutOfScopeEvenIfTheNoteIdWouldOtherwiseExist() {
        UUID noteId = UUID.randomUUID();
        // Deliberately stubbed to prove requireVisible short-circuits before this lookup runs.
        when(noteRepository.findByIdAndDocumentId(noteId, documentId))
                .thenReturn(Optional.of(existingNote(documentId, noteId, OffsetDateTime.now())));
        doThrow(new NotFoundException("Document not found"))
                .when(documentScopeGuard)
                .requireVisible(user, documentId);

        assertThatThrownBy(() -> notesService.update(user, documentId, noteId, new UpdateNoteDto("mới", null)))
                .isInstanceOf(NotFoundException.class);

        verify(noteRepository, never()).save(any());
    }

    // ---- regression: 409 khi expectedUpdatedAt lệch -----------------------------

    @Test
    void updateStillReturns409WhenExpectedUpdatedAtIsStale() {
        UUID noteId = UUID.randomUUID();
        OffsetDateTime realUpdatedAt = OffsetDateTime.now();
        when(noteRepository.findByIdAndDocumentId(noteId, documentId))
                .thenReturn(Optional.of(existingNote(documentId, noteId, realUpdatedAt)));

        assertThatThrownBy(() -> notesService.update(
                        user, documentId, noteId, new UpdateNoteDto("mới", realUpdatedAt.minusMinutes(5))))
                .isInstanceOf(ConflictException.class);

        verify(noteRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    // ---- audit metadata chỉ gồm documentId/noteId, không có body ---------------

    @Test
    void auditEntryForCreateOnlyContainsDocumentIdAndNoteId() {
        ArgumentCaptor<AuditService.AuditEntry> captor = ArgumentCaptor.forClass(AuditService.AuditEntry.class);

        DocumentNote created = notesService.createForDocument(user, documentId, "nội dung bí mật");

        verify(auditService).recordAsync(captor.capture());
        AuditService.AuditEntry entry = captor.getValue();
        assertThat(entry.action()).isEqualTo("note.create");
        assertThat(entry.metadata().keySet()).containsExactlyInAnyOrder("documentId", "noteId");
        assertThat(entry.metadata()).containsEntry("documentId", documentId.toString());
        assertThat(entry.metadata()).containsEntry("noteId", created.getId().toString());
        assertThat(entry.metadata().values()).doesNotContain("nội dung bí mật");
    }

    @Test
    void auditEntryForUpdateOnlyContainsDocumentIdAndNoteId() {
        UUID noteId = UUID.randomUUID();
        when(noteRepository.findByIdAndDocumentId(noteId, documentId))
                .thenReturn(Optional.of(existingNote(documentId, noteId, OffsetDateTime.now())));
        ArgumentCaptor<AuditService.AuditEntry> captor = ArgumentCaptor.forClass(AuditService.AuditEntry.class);

        notesService.update(user, documentId, noteId, new UpdateNoteDto("nội dung sửa bí mật", null));

        verify(auditService).recordAsync(captor.capture());
        AuditService.AuditEntry entry = captor.getValue();
        assertThat(entry.action()).isEqualTo("note.update");
        assertThat(entry.metadata().keySet()).containsExactlyInAnyOrder("documentId", "noteId");
        assertThat(entry.metadata()).containsEntry("noteId", noteId.toString());
        assertThat(entry.metadata().values()).doesNotContain("nội dung sửa bí mật");
    }

    // ---- Task 3: đúng 1 row outbox_event / lần create-update thành công --------

    @Test
    void createForDocumentWritesExactlyOneOutboxEventWithoutBody() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);

        notesService.createForDocument(user, documentId, "nội dung bí mật");

        verify(outboxEventRepository, times(1)).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo("document.note.changed");
        assertThat(event.getPayload().keySet()).containsExactlyInAnyOrder("documentId", "changedAt", "changedBySub");
        assertThat(event.getPayload().get("documentId")).isEqualTo(documentId.toString());
        assertThat(event.getPayload().get("changedBySub")).isEqualTo(user.sub());
        assertThat(event.getPayload().values()).doesNotContain("nội dung bí mật");
    }

    @Test
    void updateWritesExactlyOneOutboxEventWithoutBody() {
        UUID noteId = UUID.randomUUID();
        when(noteRepository.findByIdAndDocumentId(noteId, documentId))
                .thenReturn(Optional.of(existingNote(documentId, noteId, OffsetDateTime.now())));
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);

        notesService.update(user, documentId, noteId, new UpdateNoteDto("nội dung sửa bí mật", null));

        verify(outboxEventRepository, times(1)).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo("document.note.changed");
        assertThat(event.getPayload().values()).doesNotContain("nội dung sửa bí mật");
    }

    // ---- atomic ở tầng DB: lỗi ghi outbox không được nuốt lặng lẽ ---------------

    /**
     * A mocked repository cannot demonstrate a real Postgres rollback - that
     * is {@code NotesOutboxAtomicityIT}'s job (real DB, {@code @MockBean}
     * only on {@link OutboxEventRepository}). This unit test asserts the
     * precondition for Spring's rollback-on-unchecked-exception to apply at
     * all: the failure must propagate out of the {@code @Transactional}
     * method instead of being swallowed inside {@code createForDocument}.
     */
    @Test
    void outboxWriteFailurePropagatesInsteadOfBeingSwallowed() {
        when(outboxEventRepository.save(any())).thenThrow(new RuntimeException("simulated DB constraint"));

        assertThatThrownBy(() -> notesService.createForDocument(user, documentId, "nội dung"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated DB constraint");
    }
}
