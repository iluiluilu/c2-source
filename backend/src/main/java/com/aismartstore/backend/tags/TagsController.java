package com.aismartstore.backend.tags;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.tags.dto.CreateTagDto;
import com.aismartstore.backend.tags.dto.RenameTagDto;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Java port of the NestJS backend's tags/tags.controller.ts, extended per
 * classification-notes-01-tags.md §3: catalogue management for {@code
 * topic} ({@code PATCH}/{@code deactivate}, replacing the old hard-delete
 * {@code DELETE /tags/{id}}) and the cross-document report ({@code GET
 * /tags/report}).
 */
@RestController
@RequestMapping("/tags")
public class TagsController {

    private final TagsService tagsService;

    public TagsController(TagsService tagsService) {
        this.tagsService = tagsService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_tag.manage')")
    public Tag create(@CurrentUser KeycloakUser user, @Valid @RequestBody CreateTagDto dto) {
        return tagsService.create(user, dto.kind(), dto.label());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_document.read')")
    public List<Tag> list(@RequestParam(required = false) String kind) {
        return tagsService.list(kind);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_tag.manage')")
    public Tag rename(@CurrentUser KeycloakUser user, @PathVariable UUID id, @Valid @RequestBody RenameTagDto dto) {
        return tagsService.rename(user, id, dto.label());
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('PERM_tag.manage')")
    public Tag deactivate(@CurrentUser KeycloakUser user, @PathVariable UUID id) {
        return tagsService.deactivate(user, id);
    }

    /**
     * classification-notes-01-tags.md §3/§4.5 (US5): reuses {@code
     * PERM_document.read} instead of a new permission - R3 (QUAN_TRI) already
     * lacks that permission in the V12 seed, so it naturally 403s without
     * introducing a bespoke check (US5 AC-3).
     */
    @GetMapping("/report")
    @PreAuthorize("hasAuthority('PERM_document.read')")
    public List<TagsService.TagReportRow> report(
            @CurrentUser KeycloakUser user,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID folderId) {
        return tagsService.report(user, kind, from, to, folderId);
    }
}
