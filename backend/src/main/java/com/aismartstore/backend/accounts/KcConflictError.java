package com.aismartstore.backend.accounts;

/** Admin API 409 - username or email already exists. */
public class KcConflictError extends RuntimeException {
    public KcConflictError(String message) {
        super(message);
    }
}
