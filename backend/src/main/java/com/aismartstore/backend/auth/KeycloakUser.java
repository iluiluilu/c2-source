package com.aismartstore.backend.auth;

import java.util.List;

/**
 * Decoded Keycloak access token claims for the current request. Mirrors the
 * original NestJS backend's {@code KeycloakUser} interface
 * (auth/current-user.decorator.ts).
 */
public record KeycloakUser(String sub, String preferredUsername, List<String> realmRoles) {
}
