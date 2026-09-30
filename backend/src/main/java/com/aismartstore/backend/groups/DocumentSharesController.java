package com.aismartstore.backend.groups;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.groups.dto.ShareDocumentDto;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** rbac-group-scope.md §3.2: share/unshare a document to a group (Story 5 nền tảng cho Story 3 scoping). */
@RestController
@RequestMapping("/documents/{id}/shares")
public class DocumentSharesController {

    private final GroupsService groupsService;

    public DocumentSharesController(GroupsService groupsService) {
        this.groupsService = groupsService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_document.share')")
    public void share(@CurrentUser KeycloakUser user, @PathVariable UUID id, @Valid @RequestBody ShareDocumentDto dto) {
        groupsService.shareDocument(user, id, dto);
    }

    @DeleteMapping("/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_document.share')")
    public void unshare(@CurrentUser KeycloakUser user, @PathVariable UUID id, @PathVariable UUID groupId) {
        groupsService.unshareDocument(user, id, groupId);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_document.share')")
    public List<GroupsService.ShareView> list(@CurrentUser KeycloakUser user, @PathVariable UUID id) {
        return groupsService.listShares(user, id);
    }
}
