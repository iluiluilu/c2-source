package com.aismartstore.backend.accounts;

/** Admin API 404 - no such user. */
public class KcNotFoundError extends RuntimeException {
    public KcNotFoundError(String message) {
        super(message);
    }
}
