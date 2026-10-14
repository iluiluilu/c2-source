package com.aismartstore.backend.search.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * search-fulltext-01-tim-kiem-co-ban.md §3.2 - one matched document,
 * collapsed from its matched segments.
 *
 * @param documentDigitizationStatus search-fulltext-05-frontend.md §2 - the
 *     document's {@code document_digitization_status.overall_status} at
 *     search time (BA Story 4 AC-1), or {@code null} when that table has no
 *     row at all for this document (never had a digitization run created -
 *     not evidence that one is pending).
 * @param folderId / uploaderUsername / createdAt where the document lives,
 *     who ingested it and when - so a result can be judged without opening it.
 */
public record SearchHit(
        UUID documentId,
        String documentName,
        String docType,
        double score,
        List<SegmentHit> segments,
        String documentDigitizationStatus,
        UUID folderId,
        String uploaderUsername,
        OffsetDateTime createdAt) {
}
