package com.aismartstore.backend.documents;

import com.aismartstore.backend.accounts.KcNotFoundError;
import com.aismartstore.backend.accounts.KcRole;
import com.aismartstore.backend.accounts.KeycloakAdminService;
import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.BadRequestException;
import com.aismartstore.backend.common.NotFoundException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * digitization-review-03-ownership-transfer.md §4: R3-only "chuyển quyền sở
 * hữu" (change {@code document.uploader_sub}) - the only writer of that
 * column outside data-ingestion.md. Deliberately placed in the {@code
 * documents} package, not {@code digitization.review}: this never touches
 * {@code digitization_run}/{@code content_segment}, so it stays independent
 * of that module (mục 6 Task 2).
 */
@Service
public class DocumentOwnershipService {

    /** AssignableRoles.ALL's "can-bo" - the bare Keycloak realm role name for R1 (mục 4 bước 1). */
    private static final String ROLE_CAN_BO = "can-bo";

    /** AssignableRoles.ALL's "lanh-dao" - the bare Keycloak realm role name for R2 (ADR-4). */
    private static final String ROLE_LANH_DAO = "lanh-dao";

    private final DocumentRepository documentRepository;
    private final KeycloakAdminService kc;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;

    public DocumentOwnershipService(
            DocumentRepository documentRepository,
            KeycloakAdminService kc,
            AuditService auditService,
            AuditRequestContext auditRequestContext) {
        this.documentRepository = documentRepository;
        this.kc = kc;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
    }

    public record TransferResult(
            UUID documentId, String fromUploaderSub, String toUploaderSub, OffsetDateTime transferredAt) {
    }

    /**
     * mục 4 bước 1-3: validate B, update {@code uploader_sub}, write an
     * audit entry - all in one transaction (§4 bước 2/3). {@code
     * fromUploaderSub} is read fresh from {@code document} right before the
     * update (never cached), so chained transfers (AC-8, A->B->C) each get
     * their own, correct {@code fromUploaderSub}.
     */
    @Transactional
    public TransferResult transfer(UUID documentId, String toUserSub, String reason, KeycloakUser actor) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy tài liệu: " + documentId));

        String fromUploaderSub = document.getUploaderSub();
        if (toUserSub.equals(fromUploaderSub)) {
            throw new BadRequestException("Người nhận trùng người nạp hiện tại");
        }

        Map<String, Object> toUser;
        try {
            toUser = kc.getUser(toUserSub);
        } catch (KcNotFoundError e) {
            throw new BadRequestException("Tài khoản người nhận không tồn tại hoặc đã bị vô hiệu hoá");
        }
        // Same "missing enabled == enabled" default as AccountsService.toRow.
        boolean enabled = !Boolean.FALSE.equals(toUser.get("enabled"));
        if (!enabled) {
            throw new BadRequestException("Tài khoản người nhận không tồn tại hoặc đã bị vô hiệu hoá");
        }

        List<KcRole> toUserRoles = kc.listUserRealmRoles(toUserSub);
        boolean hasEligibleRole = toUserRoles.stream()
                .anyMatch(role -> ROLE_CAN_BO.equals(role.name()) || ROLE_LANH_DAO.equals(role.name()));
        if (!hasEligibleRole) {
            throw new BadRequestException("Người nhận phải có vai trò Cán bộ (R1) hoặc Lãnh đạo (R2)");
        }

        document.setUploaderSub(toUserSub);
        documentRepository.save(document);

        OffsetDateTime transferredAt = OffsetDateTime.now(ZoneOffset.UTC);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("fromUploaderSub", fromUploaderSub);
        metadata.put("toUploaderSub", toUserSub);
        if (reason != null) {
            metadata.put("reason", reason);
        }
        // §4 bước 3: synchronous record() (not recordAsync) - low-frequency
        // admin op, audit-durability over latency here.
        auditService.record(new AuditService.AuditEntry(
                actor.preferredUsername(), actor.sub(), "document.ownership_transferred", "document",
                documentId.toString(), "success", auditRequestContext.method(), auditRequestContext.path(), 200,
                auditRequestContext.ip(), metadata));

        return new TransferResult(documentId, fromUploaderSub, toUserSub, transferredAt);
    }
}
