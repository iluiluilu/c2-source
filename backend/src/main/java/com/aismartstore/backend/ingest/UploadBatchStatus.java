package com.aismartstore.backend.ingest;

/** upload_batch.status values (data-ingestion.md §2.2). */
public final class UploadBatchStatus {
    public static final String OPEN = "OPEN";
    public static final String CLOSED = "CLOSED";
    public static final String LIMIT_EXCEEDED = "LIMIT_EXCEEDED";
    public static final String EXPIRED = "EXPIRED";

    private UploadBatchStatus() {
    }
}
