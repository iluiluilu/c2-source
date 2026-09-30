package com.aismartstore.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.rbac.RolePermission;
import com.aismartstore.backend.rbac.RolePermissionRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * rbac-permission-editing.md §5 ("SecurityConfig.realmRoleAuthorities: JWT
 * có role can-bo -> sinh đúng ROLE_CAN_BO + đúng tập PERM_* khớp
 * role_permission hiện tại"), Quyết định 2 (SA). {@code realmRoleAuthorities}
 * is intentionally private (it's only ever wired as the JWT authorities
 * converter, mục 4.3) - reflection keeps that encapsulation while still unit
 * testing the mapping logic directly, without standing up a full Spring
 * context + real bearer token like {@code AccountsControllerIT} does.
 */
class SecurityConfigTest {

    private RolePermissionRepository rolePermissionRepository;
    private Cache<String, Set<String>> rolePermissionCache;
    private SecurityConfig securityConfig;
    private Method realmRoleAuthorities;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        rolePermissionRepository = mock(RolePermissionRepository.class);
        rolePermissionCache = Caffeine.newBuilder().build();
        securityConfig = new SecurityConfig(rolePermissionRepository, rolePermissionCache);
        realmRoleAuthorities = SecurityConfig.class.getDeclaredMethod("realmRoleAuthorities", Jwt.class);
        realmRoleAuthorities.setAccessible(true);
    }

    private static RolePermission grant(String roleCode, String permissionCode) {
        return new RolePermission(roleCode, permissionCode, OffsetDateTime.now(), null);
    }

    private static Jwt jwtWithRealmRoles(String... roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    @SuppressWarnings("unchecked")
    private Set<String> invoke(Jwt jwt) throws Exception {
        Collection<GrantedAuthority> authorities =
                (Collection<GrantedAuthority>) realmRoleAuthorities.invoke(securityConfig, jwt);
        return authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    @Test
    void canBoRoleYieldsRoleAuthorityAndMatchingPermissionAuthorities() throws Exception {
        when(rolePermissionRepository.findByRoleCode("CAN_BO"))
                .thenReturn(List.of(grant("CAN_BO", "document.read"), grant("CAN_BO", "tag.manage")));

        Set<String> authorities = invoke(jwtWithRealmRoles("can-bo"));

        assertThat(authorities).containsExactlyInAnyOrder(
                "ROLE_CAN_BO", "PERM_document.read", "PERM_tag.manage");
    }

    @Test
    void noRealmRolesYieldsNoAuthorities() throws Exception {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "x")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        assertThat(invoke(jwt)).isEmpty();
    }

    /** mục 4.3 note 2: a second lookup for the same role within the TTL must not hit the repository again. */
    @Test
    void secondLookupWithinTtlUsesCacheNotRepository() throws Exception {
        when(rolePermissionRepository.findByRoleCode("QUAN_TRI"))
                .thenReturn(List.of(grant("QUAN_TRI", "account.manage")));

        invoke(jwtWithRealmRoles("quan-tri"));
        invoke(jwtWithRealmRoles("quan-tri"));

        org.mockito.Mockito.verify(rolePermissionRepository, org.mockito.Mockito.times(1))
                .findByRoleCode("QUAN_TRI");
    }

    @Test
    void multipleRealmRolesYieldTheUnionOfTheirPermissions() throws Exception {
        when(rolePermissionRepository.findByRoleCode("CAN_BO"))
                .thenReturn(List.of(grant("CAN_BO", "document.read")));
        when(rolePermissionRepository.findByRoleCode("LANH_DAO"))
                .thenReturn(List.of(grant("LANH_DAO", "audit.read")));

        Set<String> authorities = invoke(jwtWithRealmRoles("can-bo", "lanh-dao"));

        assertThat(authorities).containsExactlyInAnyOrder(
                "ROLE_CAN_BO", "ROLE_LANH_DAO", "PERM_document.read", "PERM_audit.read");
    }
}
