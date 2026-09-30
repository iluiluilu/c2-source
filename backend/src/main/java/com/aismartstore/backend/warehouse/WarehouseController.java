package com.aismartstore.backend.warehouse;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.warehouse.dto.CreateFolderDto;
import com.aismartstore.backend.warehouse.dto.MoveFolderDto;
import com.aismartstore.backend.warehouse.dto.RenameFolderDto;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Java port of the NestJS backend's warehouse/warehouse.controller.ts, extended with move (Story 2). */
@RestController
@RequestMapping("/folders")
public class WarehouseController {

    private final WarehouseService warehouseService;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;

    public WarehouseController(
            WarehouseService warehouseService, AuditService auditService, AuditRequestContext auditRequestContext) {
        this.warehouseService = warehouseService;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
    }

    @GetMapping("/tree")
    @PreAuthorize("hasAuthority('PERM_folder.read')")
    public List<FolderNode> tree() {
        return warehouseService.getTree();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_folder.manage')")
    public WarehouseFolder create(@CurrentUser KeycloakUser user, @Valid @RequestBody CreateFolderDto dto) {
        WarehouseFolder folder = warehouseService.createFolder(user, dto.name(), dto.parentId());
        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "folder.create", "warehouse_folder",
                folder.getId().toString(), "success", auditRequestContext.method(), auditRequestContext.path(), 200,
                auditRequestContext.ip(),
                java.util.Map.of("name", folder.getName())));
        return folder;
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_folder.manage')")
    public WarehouseFolder rename(
            @CurrentUser KeycloakUser user, @PathVariable UUID id, @Valid @RequestBody RenameFolderDto dto) {
        WarehouseFolder folder = warehouseService.renameFolder(user, id, dto.name());
        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "folder.rename", "warehouse_folder",
                folder.getId().toString(), "success", auditRequestContext.method(), auditRequestContext.path(), 200,
                auditRequestContext.ip(),
                java.util.Map.of("name", folder.getName())));
        return folder;
    }

    @PatchMapping("/{id}/move")
    @PreAuthorize("hasAuthority('PERM_folder.manage')")
    public WarehouseFolder move(
            @CurrentUser KeycloakUser user, @PathVariable UUID id, @Valid @RequestBody MoveFolderDto dto) {
        WarehouseFolder folder = warehouseService.moveFolder(user, id, dto.newParentId());
        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "folder.move", "warehouse_folder",
                folder.getId().toString(), "success", auditRequestContext.method(), auditRequestContext.path(), 200,
                auditRequestContext.ip(),
                java.util.Map.of("newParentId", dto.newParentId() == null ? "" : dto.newParentId().toString())));
        return folder;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_folder.manage')")
    public void delete(@CurrentUser KeycloakUser user, @PathVariable UUID id) {
        warehouseService.deleteFolder(id);
        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "folder.delete", "warehouse_folder",
                id.toString(), "success", auditRequestContext.method(), auditRequestContext.path(), 204,
                auditRequestContext.ip(), null));
    }
}
