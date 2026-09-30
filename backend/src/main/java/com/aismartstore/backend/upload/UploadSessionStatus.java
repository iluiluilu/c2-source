package com.aismartstore.backend.upload;

/**
 * {@code upload_session.status} values
 * (chunked-file-upload-01-ingest-chunked-upload.md §2.1). Plain string
 * constants (not {@code @Enumerated}), matching the existing
 * {@code UploadBatchStatus} pattern in {@code com.aismartstore.backend.ingest}.
 */
public final class UploadSessionStatus {
    public static final String OPEN = "OPEN";
    public static final String COMPLETED = "COMPLETED";
    public static final String ABORTED = "ABORTED";
    public static final String EXPIRED = "EXPIRED";

    private UploadSessionStatus() {
    }
}
