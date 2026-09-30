package com.aismartstore.backend.scanning;

/**
 * clamd is unreachable or misbehaving. Callers must treat this as fail-closed
 * (FR-12): reject the upload with 503, store nothing.
 */
public class ScannerUnavailableException extends RuntimeException {
    public ScannerUnavailableException(String message) {
        super(message);
    }
}
