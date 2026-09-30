package com.aismartstore.backend.common;

import org.springframework.http.HttpStatus;

public class ServiceUnavailableException extends ApiException {
    public ServiceUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    /** With an explicit body (e.g. {@code {code, message, ...}}) - see personal-storage-quota.md §3. */
    public ServiceUnavailableException(Object body, String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, body, message);
    }
}
