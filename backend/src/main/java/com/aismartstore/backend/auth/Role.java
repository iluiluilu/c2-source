package com.aismartstore.backend.auth;

/**
 * Role constants matching Keycloak realm roles (see
 * main-workspace/keycloak/realm-export.json). Mirrors the original NestJS
 * backend's auth/roles.constant.ts — R1/R2/R3 from the SRS (section 1).
 *
 * Spring authorities are emitted as {@code ROLE_<name>} by
 * {@link com.aismartstore.backend.config.SecurityConfig}'s JWT authorities
 * converter, so controllers use {@code @PreAuthorize("hasRole(Role.CAN_BO)")}.
 */
public final class Role {
    /** R1 - Cán bộ (officer). Keycloak realm role: can-bo. */
    public static final String CAN_BO = "CAN_BO";
    /** R2 - Lãnh đạo (leadership). Keycloak realm role: lanh-dao. */
    public static final String LANH_DAO = "LANH_DAO";
    /** R3 - Quản trị hệ thống (system admin). Keycloak realm role: quan-tri. */
    public static final String QUAN_TRI = "QUAN_TRI";

    private Role() {
    }
}
