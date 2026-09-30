package com.aismartstore.backend.accounts;

/**
 * Keycloak is unreachable, returned a 5xx, or rejected the service account
 * itself (401/403 from the Admin API - a deployment problem, not a caller
 * problem). Callers must surface this as 503, never as a user-input error.
 */
public class KeycloakUnavailableError extends RuntimeException {
    public KeycloakUnavailableError(String message) {
        super(message);
    }
}
