package com.aismartstore.backend.common;

import org.springframework.http.HttpStatus;

/** A recognized/authenticated caller lacking permission for this specific resource instance. */
public class ForbiddenException extends ApiException {
    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }
}
