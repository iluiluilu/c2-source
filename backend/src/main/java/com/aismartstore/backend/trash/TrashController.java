package com.aismartstore.backend.trash;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.trash.dto.RestoreBatchDto;
import com.aismartstore.backend.trash.dto.RestoreDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thùng rác (delete-restore-trash-01-xoa-mem-thung-rac.md §3): view/restore
 * (Story 2, {@code PERM_trash.manage}) and stats (Story 3,
 * {@code PERM_trash.stats.read}, R3-only). The 2 xóa mềm mutation endpoints
 * ({@code POST /documents/trash}/{@code -preview}) live in {@code
 * DocumentsController} instead, per §1.
 */
@RestController
@RequestMapping("/trash")
@Validated
public class TrashController {

    private final TrashService trashService;

    public TrashController(TrashService trashService) {
        this.trashService = trashService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_trash.manage')")
    public TrashService.TrashList list(
            @CurrentUser KeycloakUser user,
            @RequestParam(required = false) String view,
            @RequestParam(required = false) @Min(1) @Max(200) Integer limit,
            @RequestParam(required = false) @Min(0) Integer offset) {
        return trashService.list(user, view, limit, offset);
    }

    /**
     * Story 3 (R3): static literal segment, matched ahead of {@code /{id}}
     * by Spring's PathPattern specificity ordering regardless of
     * declaration order (same idiom as {@code
     * DocumentsController#unclassifiedDocType}).
     */
    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('PERM_trash.stats.read')")
    public TrashService.TrashStats stats() {
        return trashService.stats();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_trash.manage')")
    public TrashService.TrashDetail findOne(@CurrentUser KeycloakUser user, @PathVariable UUID id) {
        return trashService.getOne(user, id);
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('PERM_trash.manage')")
    public TrashService.RestoreResult restore(
            @CurrentUser KeycloakUser user, @PathVariable UUID id, @Valid @RequestBody(required = false) RestoreDto dto) {
        RestoreDto body = dto != null ? dto : new RestoreDto();
        return trashService.restore(user, id, body.targetFolderId(), body.onNameConflict());
    }

    @PostMapping("/restore-batch")
    @PreAuthorize("hasAuthority('PERM_trash.manage')")
    public Map<String, Object> restoreBatch(@CurrentUser KeycloakUser user, @Valid @RequestBody RestoreBatchDto dto) {
        return Map.of(
                "results", trashService.restoreBatch(user, dto.documentIds(), dto.targetFolderId(), dto.onNameConflict()));
    }
}
