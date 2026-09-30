package com.aismartstore.backend.tags;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.tags.dto.AssignTagDto;
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

/**
 * Java port of the NestJS backend's tags/document-tags.controller.ts.
 * classification-notes-01-tags.md §4.1: every entrypoint now goes through
 * {@code DocumentScopeGuard} (inside {@link TagsService}), closing the scope
 * gap flagged by SA Quyết định 4/Rủi ro mục 6.
 */
@RestController
@RequestMapping("/documents/{documentId}/tags")
public class DocumentTagsController {

    private final TagsService tagsService;

    public DocumentTagsController(TagsService tagsService) {
        this.tagsService = tagsService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_document.read')")
    public List<Tag> list(@CurrentUser KeycloakUser user, @PathVariable UUID documentId) {
        return tagsService.listForDocument(user, documentId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_tag.manage')")
    public void assign(
            @CurrentUser KeycloakUser user, @PathVariable UUID documentId, @Valid @RequestBody AssignTagDto dto) {
        tagsService.assign(user, documentId, dto.tagId(), dto.label());
    }

    @DeleteMapping("/{tagId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_tag.manage')")
    public void unassign(@CurrentUser KeycloakUser user, @PathVariable UUID documentId, @PathVariable UUID tagId) {
        tagsService.unassign(user, documentId, tagId);
    }
}
