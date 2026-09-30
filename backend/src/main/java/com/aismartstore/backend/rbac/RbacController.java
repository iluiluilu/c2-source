package com.aismartstore.backend.rbac;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.rbac.dto.UpdateRolePermissionsDto;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * rbac-permission-editing.md §3: the RBAC admin surface itself. Deliberately
 * {@code hasRole('QUAN_TRI')} everywhere - not {@code hasAuthority('PERM_...')}
 * like every other controller after Task 6 - so R3 can never lock itself out
 * of the one screen that would undo a bad permission edit (mirrors the
 * lockout guard in {@link RbacService}, mục 3/Quyết định 3).
 */
@RestController
@RequestMapping("/admin/rbac")
@PreAuthorize("hasRole('QUAN_TRI')")
public class RbacController {

    private final RbacService rbacService;

    public RbacController(RbacService rbacService) {
        this.rbacService = rbacService;
    }

    /** Story 1: the full role x permission matrix. */
    @GetMapping("/roles")
    public List<RbacService.RoleView> listRoles() {
        return rbacService.listRoles();
    }

    /** Story 1: the closed permission catalogue. */
    @GetMapping("/permissions")
    public List<RbacService.PermissionView> listPermissions() {
        return rbacService.listPermissions();
    }

    /** Story 2/3: full-replace the permission set for one role. */
    @PutMapping("/roles/{roleCode}/permissions")
    public RbacService.UpdateResult updatePermissions(
            @CurrentUser KeycloakUser user,
            @PathVariable String roleCode,
            @Valid @RequestBody UpdateRolePermissionsDto dto) {
        return rbacService.updateRolePermissions(user, roleCode, dto.permissionCodes());
    }

    /** Story 2 AC-5: called before the confirm/save click, to show the impact warning. */
    @GetMapping("/roles/{roleCode}/preview-impact")
    public RbacService.PreviewImpactResult previewImpact(
            @PathVariable String roleCode,
            @RequestParam(required = false) List<String> permissionCodes) {
        return rbacService.previewImpact(roleCode, permissionCodes == null ? List.of() : permissionCodes);
    }
}
