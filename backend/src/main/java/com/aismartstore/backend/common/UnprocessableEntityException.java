package com.aismartstore.backend.common;

import org.springframework.http.HttpStatus;

public class UnprocessableEntityException extends ApiException {
    public UnprocessableEntityException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    /** With an explicit body (e.g. {@code {code, message, ...}}) - see personal-storage-quota.md §3. */
    public UnprocessableEntityException(Object body, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, body, message);
    }
}
