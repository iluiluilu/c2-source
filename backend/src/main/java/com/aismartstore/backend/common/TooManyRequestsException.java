package com.aismartstore.backend.common;

import org.springframework.http.HttpStatus;

/** 429 - a per-user or system-wide concurrency limit was hit (export-reporting AC-30). */
public class TooManyRequestsException extends ApiException {
    public TooManyRequestsException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    /** With an explicit body (e.g. {@code {code: "SEMANTIC_BUSY", message}}) - search-semantic AC-5.3. */
    public TooManyRequestsException(Object body, String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, body, message);
    }
}
