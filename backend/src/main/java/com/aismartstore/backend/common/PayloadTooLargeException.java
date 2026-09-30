package com.aismartstore.backend.common;

import org.springframework.http.HttpStatus;

public class PayloadTooLargeException extends ApiException {
    public PayloadTooLargeException(Object body, String message) {
        super(HttpStatus.PAYLOAD_TOO_LARGE, body, message);
    }
}
