package com.aismartstore.backend.common;

import org.springframework.http.HttpStatus;

/** Base for exceptions that map directly to an HTTP status, message body. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final transient Object body;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
        this.body = null;
    }

    public ApiException(HttpStatus status, Object body, String message) {
        super(message);
        this.status = status;
        this.body = body;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Object getBody() {
        return body;
    }
}
