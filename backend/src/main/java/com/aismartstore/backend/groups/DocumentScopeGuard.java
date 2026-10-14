package com.aismartstore.backend.groups;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ConflictException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/**
 * Shared "load one document, enforcing {@link BusinessDataScope}" used by
 * every read entrypoint keyed off a single {@code documentId} (rbac-group-
 * scope.md Task 8: {@code DocumentsService.findOne/getContent} and {@code
 * NotesService.listForDocument} - a plain component instead of duplicating
 * this logic in both services, and instead of a dependency cycle (
 * {@code DocumentsService} already depends on {@code NotesService} for the
 * upload-time note, so {@code NotesService} cannot depend back on {@code
 * DocumentsService}).
 */
@Service
public class DocumentScopeGuard {

    private final DocumentRepository documentRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;

    public DocumentScopeGuard(
            DocumentRepository documentRepository,
            GroupMemberRepository groupMemberRepository,
            AuditService auditService,
            AuditRequestContext auditRequestContext) {
        this.documentRepository = documentRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
    }

    /**
     * Returns {@code documentId} if it is within {@code user}'s scope.
     * Otherwise throws {@link NotFoundException} - deliberately the same 404
     * whether the document does not exist at all or exists but is outside
     * scope (Story 3 AC-4: "không phân biệt 'không tồn tại' với 'không có
     * quyền', tránh rò rỉ thông tin tồn tại của tài liệu"). In the
     * out-of-scope case only, an {@code access.denied} audit entry is
     * recorded from a second, scope-free lookup used solely for that log -
     * its result is never returned to the caller.
     */
    public Document requireVisible(KeycloakUser user, UUID documentId) {
        Specification<Document> scope = BusinessDataScope.forDocuments(user.sub(), groupMemberRepository);
        Specification<Document> byId = (root, query, cb) -> cb.equal(root.get("id"), documentId);

        return documentRepository
                .findOne(scope.and(byId))
                .orElseGet(() -> {
                    documentRepository.findById(documentId).ifPresent(raw -> auditService.recordAsync(
                            new AuditService.AuditEntry(
                                    user.preferredUsername(),
                                    user.sub(),
                                    "access.denied",
                                    "document",
                                    documentId.toString(),
                                    "failure",
                                    auditRequestContext.method(),
                                    auditRequestContext.path(),
                                    404,
                                    auditRequestContext.ip(),
                                    Map.of("reason", "out_of_scope"))));
                    throw new NotFoundException("Không tìm thấy tài liệu");
                });
    }

    /**
     * {@link #requireVisible} plus "the document is {@code clean}": tagging, noting and sharing a rejected/infected
     * FR-12 evidence row is refused with 409 (feedbacks D-08/S-01) - those writes would otherwise push it into the
     * search index and into group members' view. Removing a tag/note/share stays allowed (plain {@code requireVisible}).
     */
    public Document requireVisibleClean(KeycloakUser user, UUID documentId) {
        Document document = requireVisible(user, documentId);
        if (!isClean(document)) {
            throw new ConflictException(NOT_CLEAN_MESSAGE);
        }
        return document;
    }

    public static boolean isClean(Document document) {
        return DocumentStatus.CLEAN.equals(document.getStatus());
    }

    public static final String NOT_CLEAN_MESSAGE =
            "Tài liệu bị từ chối hoặc nhiễm mã độc, không thể gán nhãn, ghi chú hay chia sẻ.";
}
