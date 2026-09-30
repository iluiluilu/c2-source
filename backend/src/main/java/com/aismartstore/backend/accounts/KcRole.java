package com.aismartstore.backend.accounts;

/** Subset of Keycloak's RoleRepresentation needed for role-mapping calls. */
public record KcRole(String id, String name) {
}
