package com.aismartstore.backend.accounts;

/** Admin API 400 - carries Keycloak's errorMessage (e.g. password policy). */
public class KcBadRequestError extends RuntimeException {
    public KcBadRequestError(String message) {
        super(message);
    }
}
