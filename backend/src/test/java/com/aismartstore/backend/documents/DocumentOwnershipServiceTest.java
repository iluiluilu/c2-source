package com.aismartstore.backend.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.accounts.KcNotFoundError;
import com.aismartstore.backend.accounts.KcRole;
import com.aismartstore.backend.accounts.KeycloakAdminService;
import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.BadRequestException;
import com.aismartstore.backend.common.NotFoundException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit coverage for digitization-review-03-ownership-transfer.md §5
 * ("Unit test"): {@code DocumentOwnershipService.transfer} validation of B
 * (AC-1), independent of Keycloak/Postgres.
 */
class DocumentOwnershipServiceTest {

    private DocumentRepository documentRepository;
    private KeycloakAdminService kc;
    private AuditService auditService;
    private AuditRequestContext auditRequestContext;
    private DocumentOwnershipService service;

    private final KeycloakUser admin = new KeycloakUser("admin-sub", "admin", List.of("quan-tri"));

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        kc = mock(KeycloakAdminService.class);
        auditService = mock(AuditService.class);
        auditRequestContext = mock(AuditRequestContext.class);
        service = new DocumentOwnershipService(documentRepository, kc, auditService, auditRequestContext);

        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Document documentRow(UUID id, String uploaderSub) {
        Document document = new Document();
        document.setOriginalName("scan.pdf");
        document.setMimeType("application/pdf");
        document.setSize(10);
        document.setUploaderSub(uploaderSub);
        document.setUploaderUsername(uploaderSub);
        document.setStatus(DocumentStatus.CLEAN);
        document.setCreatedAt(OffsetDateTime.now());
        return document;
    }

    private static Map<String, Object> kcUserRep(boolean enabled) {
        return Map.of("id", "user-b", "username", "userB", "enabled", enabled);
    }

    @Test
    void transferRejectsWhenDocumentNotFound() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transfer(documentId, "user-b", null, admin))
                .isInstanceOf(NotFoundException.class);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void transferRejectsWhenBIsSameAsCurrentUploader() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(documentRow(documentId, "user-a")));

        assertThatThrownBy(() -> service.transfer(documentId, "user-a", null, admin))
                .isInstanceOf(BadRequestException.class);
        verify(documentRepository, never()).save(any());
        verify(kc, never()).getUser(any());
    }

    @Test
    void transferRejectsWhenBDoesNotExistInKeycloak() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(documentRow(documentId, "user-a")));
        when(kc.getUser("user-b")).thenThrow(new KcNotFoundError("not found"));

        assertThatThrownBy(() -> service.transfer(documentId, "user-b", null, admin))
                .isInstanceOf(BadRequestException.class);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void transferRejectsWhenBIsDisabled() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(documentRow(documentId, "user-a")));
        when(kc.getUser("user-b")).thenReturn(kcUserRep(false));

        assertThatThrownBy(() -> service.transfer(documentId, "user-b", null, admin))
                .isInstanceOf(BadRequestException.class);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void transferRejectsWhenBLacksCanBoRole() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(documentRow(documentId, "user-a")));
        when(kc.getUser("user-b")).thenReturn(kcUserRep(true));
        when(kc.listUserRealmRoles("user-b")).thenReturn(List.of(new KcRole("r1", "lanh-dao")));

        assertThatThrownBy(() -> service.transfer(documentId, "user-b", null, admin))
                .isInstanceOf(BadRequestException.class);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void transferSucceedsWhenBIsAnActiveCanBoAccount() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(documentRow(documentId, "user-a")));
        when(kc.getUser("user-b")).thenReturn(kcUserRep(true));
        when(kc.listUserRealmRoles("user-b")).thenReturn(List.of(new KcRole("r1", "can-bo")));

        DocumentOwnershipService.TransferResult result = service.transfer(documentId, "user-b", "chuyển việc", admin);

        assertThat(result.documentId()).isEqualTo(documentId);
        assertThat(result.fromUploaderSub()).isEqualTo("user-a");
        assertThat(result.toUploaderSub()).isEqualTo("user-b");
        assertThat(result.transferredAt()).isNotNull();

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        assertThat(captor.getValue().getUploaderSub()).isEqualTo("user-b");

        ArgumentCaptor<AuditService.AuditEntry> auditCaptor = ArgumentCaptor.forClass(AuditService.AuditEntry.class);
        verify(auditService).record(auditCaptor.capture());
        AuditService.AuditEntry entry = auditCaptor.getValue();
        assertThat(entry.action()).isEqualTo("document.ownership_transferred");
        assertThat(entry.metadata()).containsEntry("fromUploaderSub", "user-a");
        assertThat(entry.metadata()).containsEntry("toUploaderSub", "user-b");
        assertThat(entry.metadata()).containsEntry("reason", "chuyển việc");
    }

    @Test
    void transferWithoutReasonSucceedsAndOmitsReasonFromAuditMetadata() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(documentRow(documentId, "user-a")));
        when(kc.getUser("user-b")).thenReturn(kcUserRep(true));
        when(kc.listUserRealmRoles("user-b")).thenReturn(List.of(new KcRole("r1", "can-bo")));

        service.transfer(documentId, "user-b", null, admin);

        ArgumentCaptor<AuditService.AuditEntry> auditCaptor = ArgumentCaptor.forClass(AuditService.AuditEntry.class);
        verify(auditService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().metadata()).doesNotContainKey("reason");
    }
}
