package com.aismartstore.backend.common;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApiException {
    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }

    /** With an explicit body (e.g. {@code {code, message, ...}}) - see personal-storage-quota.md §3. */
    public ConflictException(Object body, String message) {
        super(HttpStatus.CONFLICT, body, message);
    }
}
