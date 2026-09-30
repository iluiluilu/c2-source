package com.aismartstore.backend.documents;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.documents.dto.TransferOwnershipDto;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * digitization-review-03-ownership-transfer.md §3: R3-only "chuyển quyền sở
 * hữu tài liệu" - metadata lookup (never digitized content, AC-5) plus the
 * transfer action itself. Same {@code /admin/...} route family and
 * {@code PERM_document.transfer_ownership} (V28, role QUAN_TRI only) as the
 * spec's API contract table.
 */
@RestController
@RequestMapping("/admin/document-ownership")
public class DocumentOwnershipController {

    private static final int MAX_PAGE_SIZE = 200;

    private final DocumentOwnershipRepository documentOwnershipRepository;
    private final DocumentOwnershipService documentOwnershipService;

    public DocumentOwnershipController(
            DocumentOwnershipRepository documentOwnershipRepository,
            DocumentOwnershipService documentOwnershipService) {
        this.documentOwnershipRepository = documentOwnershipRepository;
        this.documentOwnershipService = documentOwnershipService;
    }

    /**
     * {@code GET /admin/document-ownership} response shape (§3 table): a
     * small explicit DTO instead of returning {@link Page} directly, same
     * pattern as {@code AuditController.AuditPage} ({@code PageImpl}'s
     * default JSON serialization is explicitly unstable/discouraged).
     */
    public record DocumentOwnershipPageView(
            List<DocumentOwnershipRow> content, long totalElements, int page, int size, int totalPages) {
        static DocumentOwnershipPageView from(Page<DocumentOwnershipRow> page) {
            return new DocumentOwnershipPageView(
                    page.getContent(), page.getTotalElements(), page.getNumber(), page.getSize(),
                    page.getTotalPages());
        }
    }

    /** {@code POST .../transfer} response shape (§3 table). */
    public record TransferResponse(
            UUID documentId, String fromUploaderSub, String toUploaderSub, OffsetDateTime transferredAt) {
        static TransferResponse from(DocumentOwnershipService.TransferResult result) {
            return new TransferResponse(
                    result.documentId(), result.fromUploaderSub(), result.toUploaderSub(), result.transferredAt());
        }
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_document.transfer_ownership')")
    public DocumentOwnershipPageView list(
            @RequestParam String uploaderSub,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return DocumentOwnershipPageView.from(
                documentOwnershipRepository.findByUploaderSub(uploaderSub, Math.max(page, 0), clampedSize));
    }

    @PostMapping("/{documentId}/transfer")
    @PreAuthorize("hasAuthority('PERM_document.transfer_ownership')")
    public TransferResponse transfer(
            @CurrentUser KeycloakUser user,
            @PathVariable UUID documentId,
            @Valid @RequestBody TransferOwnershipDto body) {
        return TransferResponse.from(
                documentOwnershipService.transfer(documentId, body.toUserSub(), body.reason(), user));
    }
}
