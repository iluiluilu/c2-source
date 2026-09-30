package com.aismartstore.backend.documents;

import java.util.UUID;

/**
 * digitization-review-03-ownership-transfer.md §3.1: one row of {@code GET
 * /admin/document-ownership} - metadata-only (no digitized content), just
 * enough for R3 to decide whether/what to transfer. {@code
 * digitizationStatus} carries all 4 {@code document_digitization_status}
 * columns (chốt 2026-09-08 - not just ocr/stt) so R3 also sees SED/video
 * summary review state before deciding.
 */
public record DocumentOwnershipRow(
        UUID documentId, String originalName, String uploaderSub, DigitizationStatus digitizationStatus) {

    public record DigitizationStatus(String ocrStatus, String sttStatus, String sedStatus, String videoSummaryStatus) {
    }
}
