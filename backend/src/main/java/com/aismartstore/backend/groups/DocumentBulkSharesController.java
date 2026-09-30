package com.aismartstore.backend.groups;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.groups.dto.BulkShareDto;
import com.aismartstore.backend.groups.dto.BulkShareResultItem;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * group-management-sharing-02-document-sharing.md §3.1 (Story 1): {@code
 * POST /documents/shares} - one group, up to 100 documents in one request.
 * A separate controller (not a method on {@link DocumentSharesController},
 * which is mapped under {@code /documents/{id}/shares}) because this route
 * has no {@code {id}} path segment.
 */
@RestController
@RequestMapping("/documents")
public class DocumentBulkSharesController {

    private final GroupsService groupsService;

    public DocumentBulkSharesController(GroupsService groupsService) {
        this.groupsService = groupsService;
    }

    /** {@code POST /documents/shares} response shape (§3.1). */
    public record BulkShareResponse(List<BulkShareResultItem> results) {
    }

    @PostMapping("/shares")
    @PreAuthorize("hasAuthority('PERM_document.share')")
    public BulkShareResponse shareBulk(@CurrentUser KeycloakUser user, @Valid @RequestBody BulkShareDto dto) {
        return new BulkShareResponse(groupsService.shareDocumentsBulk(user, dto));
    }
}
