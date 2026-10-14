package com.aismartstore.backend.common;

import org.springframework.http.HttpStatus;

/** 410 - the resource existed but is no longer available (export-reporting: expired export file). */
public class GoneException extends ApiException {
    public GoneException(String message) {
        super(HttpStatus.GONE, message);
    }
}
