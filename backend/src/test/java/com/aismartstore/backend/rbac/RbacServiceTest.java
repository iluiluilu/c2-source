package com.aismartstore.backend.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.accounts.KeycloakAdminService;
import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.common.BadRequestException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** rbac-permission-editing.md §5 ("Unit test" - RbacService.updateRolePermissions / guardLockout). */
class RbacServiceTest {

    private static final KeycloakUser R3 = new KeycloakUser("r3-sub", "admin", List.of("quan-tri"));

    private RoleRepository roleRepository;
    private PermissionRepository permissionRepository;
    private RolePermissionRepository rolePermissionRepository;
    private KeycloakAdminService kc;
    private AuditService auditService;
    private Cache<String, Set<String>> rolePermissionCache;
    private RbacService service;

    private static final List<PermissionEntity> CATALOGUE = List.of(
            permission("document.read"), permission("document.upload"), permission("note.write"),
            permission("tag.manage"), permission("folder.read"), permission("folder.manage"),
            permission("scan_allowlist.manage"), permission("system_config.manage"), permission("account.read"),
            permission("account.manage"), permission("account.self_service"), permission("audit.read"),
            permission("personal.manage"), permission("group.manage"), permission("document.share"));

    private static PermissionEntity permission(String code) {
        PermissionEntity p = new PermissionEntity();
        p.setCode(code);
        p.setDescription(code);
        return p;
    }

    private static RolePermission grant(String roleCode, String permissionCode) {
        return new RolePermission(roleCode, permissionCode, OffsetDateTime.now(), null);
    }

    @BeforeEach
    void setUp() {
        roleRepository = mock(RoleRepository.class);
        permissionRepository = mock(PermissionRepository.class);
        rolePermissionRepository = mock(RolePermissionRepository.class);
        kc = mock(KeycloakAdminService.class);
        auditService = mock(AuditService.class);
        AuditRequestContext auditRequestContext = mock(AuditRequestContext.class);
        rolePermissionCache = Caffeine.newBuilder().build();
        service = new RbacService(
                roleRepository, permissionRepository, rolePermissionRepository, kc, auditService,
                auditRequestContext, rolePermissionCache);

        when(permissionRepository.findAll()).thenReturn(CATALOGUE);
        when(kc.listRoleUsers(any(), anyInt())).thenReturn(List.of());
    }

    // ---- validation (Story 2 AC-1) ------------------------------------------

    @Test
    void updateRejectsUnknownRoleCode() {
        assertThatThrownBy(() -> service.updateRolePermissions(R3, "NOT_A_ROLE", List.of("document.read")))
                .isInstanceOf(BadRequestException.class);
        verify(rolePermissionRepository, never()).save(any());
    }

    @Test
    void updateRejectsUnknownPermissionCode() {
        when(rolePermissionRepository.findByRoleCode("CAN_BO")).thenReturn(List.of());
        assertThatThrownBy(
                        () -> service.updateRolePermissions(R3, "CAN_BO", List.of("document.delete_all")))
                .isInstanceOf(BadRequestException.class);
        verify(rolePermissionRepository, never()).save(any());
    }

    // ---- diff added/removed -------------------------------------------------

    @Test
    void updateComputesAddedAndRemovedAndWritesBoth() {
        when(rolePermissionRepository.findByRoleCode("CAN_BO"))
                .thenReturn(List.of(grant("CAN_BO", "document.read"), grant("CAN_BO", "tag.manage")));

        RbacService.UpdateResult result = service.updateRolePermissions(
                R3, "CAN_BO", List.of("document.read", "note.write"));

        assertThat(result.permissionCodes()).containsExactly("document.read", "note.write");
        verify(rolePermissionRepository).deleteByRoleCodeAndPermissionCode("CAN_BO", "tag.manage");
        verify(rolePermissionRepository).save(argThatRolePermission("CAN_BO", "note.write"));

        org.mockito.ArgumentCaptor<AuditService.AuditEntry> captor =
                org.mockito.ArgumentCaptor.forClass(AuditService.AuditEntry.class);
        verify(auditService).recordAsync(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("rbac.role_permission_update");
        assertThat(captor.getValue().resourceId()).isEqualTo("CAN_BO");
        assertThat(captor.getValue().metadata()).containsEntry("permissionsAdded", List.of("note.write"));
        assertThat(captor.getValue().metadata()).containsEntry("permissionsRemoved", List.of("tag.manage"));
    }

    /** mục 5 "case biên": an identical set is a no-op - still 200, but no audit write, no cache evict. */
    @Test
    void updateWithNoChangesSkipsAuditAndCacheEviction() {
        when(rolePermissionRepository.findByRoleCode("CAN_BO"))
                .thenReturn(List.of(grant("CAN_BO", "document.read")));
        rolePermissionCache.put("CAN_BO", Set.of("document.read"));

        RbacService.UpdateResult result =
                service.updateRolePermissions(R3, "CAN_BO", List.of("document.read"));

        assertThat(result.permissionCodes()).containsExactly("document.read");
        verify(rolePermissionRepository, never()).save(any());
        verify(rolePermissionRepository, never()).deleteByRoleCodeAndPermissionCode(any(), any());
        verify(auditService, never()).recordAsync(any());
        // still cached (not evicted) since nothing changed
        assertThat(rolePermissionCache.getIfPresent("CAN_BO")).isEqualTo(Set.of("document.read"));
    }

    @Test
    void updateEvictsCacheOnChange() {
        when(rolePermissionRepository.findByRoleCode("CAN_BO")).thenReturn(List.of());
        rolePermissionCache.put("CAN_BO", Set.of("stale"));

        service.updateRolePermissions(R3, "CAN_BO", List.of("document.read"));

        assertThat(rolePermissionCache.getIfPresent("CAN_BO")).isNull();
    }

    // ---- guardLockout (Story 3 AC-1/AC-2, Quyết định 3) ------------------------

    @Test
    void updateBlocksRemovingAccountManageFromQuanTri() {
        when(rolePermissionRepository.findByRoleCode("QUAN_TRI")).thenReturn(List.of(
                grant("QUAN_TRI", "account.manage"), grant("QUAN_TRI", "system_config.manage")));

        assertThatThrownBy(() -> service.updateRolePermissions(R3, "QUAN_TRI", List.of("system_config.manage")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .satisfies(e -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = (Map<String, Object>) ((ApiException) e).getBody();
                    assertThat(body).containsEntry("code", "RBAC_LOCKOUT");
                });
        verify(rolePermissionRepository, never()).save(any());
        verify(rolePermissionRepository, never()).deleteByRoleCodeAndPermissionCode(any(), any());
    }

    @Test
    void updateBlocksRemovingSystemConfigManageFromQuanTri() {
        when(rolePermissionRepository.findByRoleCode("QUAN_TRI")).thenReturn(List.of(
                grant("QUAN_TRI", "account.manage"), grant("QUAN_TRI", "system_config.manage")));

        assertThatThrownBy(() -> service.updateRolePermissions(R3, "QUAN_TRI", List.of("account.manage")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void updateAllowsRemovingAnUnrelatedPermissionFromQuanTri() {
        when(rolePermissionRepository.findByRoleCode("QUAN_TRI")).thenReturn(List.of(
                grant("QUAN_TRI", "account.manage"), grant("QUAN_TRI", "system_config.manage"),
                grant("QUAN_TRI", "folder.manage")));

        RbacService.UpdateResult result = service.updateRolePermissions(
                R3, "QUAN_TRI", List.of("account.manage", "system_config.manage"));

        assertThat(result.permissionCodes()).containsExactlyInAnyOrder("account.manage", "system_config.manage");
        verify(rolePermissionRepository).deleteByRoleCodeAndPermissionCode("QUAN_TRI", "folder.manage");
    }

    @Test
    void guardLockoutDoesNotApplyToNonAdminRoles() {
        when(rolePermissionRepository.findByRoleCode("CAN_BO"))
                .thenReturn(List.of(grant("CAN_BO", "document.read")));

        RbacService.UpdateResult result = service.updateRolePermissions(R3, "CAN_BO", List.of());

        assertThat(result.permissionCodes()).isEmpty();
    }

    // ---- previewImpact (Story 2 AC-5) ----------------------------------------

    @Test
    void previewImpactReturnsAddedRemovedAndAffectedCount() {
        when(rolePermissionRepository.findByRoleCode("CAN_BO"))
                .thenReturn(List.of(grant("CAN_BO", "document.read"), grant("CAN_BO", "tag.manage")));
        when(kc.listRoleUsers(eq("can-bo"), anyInt())).thenReturn(List.of(Map.of("id", "u1"), Map.of("id", "u2")));

        RbacService.PreviewImpactResult result =
                service.previewImpact("CAN_BO", List.of("document.read", "note.write"));

        assertThat(result.affectedAccountCount()).isEqualTo(2);
        assertThat(result.permissionsAdded()).containsExactly("note.write");
        assertThat(result.permissionsRemoved()).containsExactly("tag.manage");
    }

    private static RolePermission argThatRolePermission(String roleCode, String permissionCode) {
        return org.mockito.ArgumentMatchers.argThat(rp -> rp != null
                && roleCode.equals(rp.getRoleCode())
                && permissionCode.equals(rp.getPermissionCode()));
    }
}
