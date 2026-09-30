package com.aismartstore.backend.search.dto;

import com.aismartstore.backend.digitization.review.SourceReferenceView;
import java.util.List;

/**
 * search-fulltext-01-tim-kiem-co-ban.md §3.2 - one of up to 3 matched
 * segments returned per document.
 *
 * @param sourceReferences search-fulltext-05-frontend.md §2 (BA Story 3
 *     AC-4) - the OCR/STT segments a {@code content_source="summary"} segment
 *     was built from, resolved from {@code content_segment.source_segment_ids}.
 *     {@code null}/empty for every {@code content_source} other than
 *     {@code summary}, and also when {@code source_segment_ids} resolves to
 *     nothing. {@code position} stays {@code type="none"} regardless - this
 *     is a separate, independent signal (a link list, not a position).
 */
public record SegmentHit(
        String segmentId,
        String contentSource,
        String confirmSource,
        String highlight,
        PositionInfo position,
        List<SourceReferenceView> sourceReferences) {
}
