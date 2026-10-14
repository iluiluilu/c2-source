package com.aismartstore.backend.groups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ConflictException;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

/** feedbacks D-08/S-01: tagging, noting and sharing only apply to a {@code clean} document. */
class DocumentScopeGuardTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentScopeGuard guard = new DocumentScopeGuard(
            documentRepository, mock(GroupMemberRepository.class), mock(AuditService.class),
            mock(AuditRequestContext.class));
    private final KeycloakUser user = new KeycloakUser("user-sub", "canbo1", List.of());

    @Test
    @SuppressWarnings("unchecked")
    void requireVisibleCleanReturnsACleanDocument() {
        Document document = withStatus("clean");
        when(documentRepository.findOne(any(Specification.class))).thenReturn(Optional.of(document));

        assertThat(guard.requireVisibleClean(user, UUID.randomUUID())).isSameAs(document);
    }

    @Test
    @SuppressWarnings("unchecked")
    void requireVisibleCleanRefusesARejectedOrInfectedDocumentWith409() {
        for (String status : List.of("rejected", "infected", "pending")) {
            when(documentRepository.findOne(any(Specification.class))).thenReturn(Optional.of(withStatus(status)));

            assertThatThrownBy(() -> guard.requireVisibleClean(user, UUID.randomUUID()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(DocumentScopeGuard.NOT_CLEAN_MESSAGE);
        }
    }

    private static Document withStatus(String status) {
        Document document = new Document();
        document.setStatus(status);
        return document;
    }
}
