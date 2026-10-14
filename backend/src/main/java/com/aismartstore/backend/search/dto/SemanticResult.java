package com.aismartstore.backend.search.dto;

import com.aismartstore.backend.digitization.review.SourceReferenceView;
import java.util.List;

/**
 * SPEC-search-semantic §2: one relevant passage. {@code excerpt} is always read from PostgreSQL
 * (never from the index, QĐ 4); no highlight (AC-2.3).
 *
 * @param contentSource   {@code ocr|stt|note|title|summary} ({@code sed} never appears, AC-2.5)
 * @param confirmSource   {@code manual|auto}, or {@code null} for title/note
 * @param cosineScore     {@code null} when the passage came from the lexical branch only
 * @param sourceReferences only for {@code summary}
 */
public record SemanticResult(
        String segmentId,
        String documentId,
        String documentName,
        String docType,
        String excerpt,
        String contentSource,
        String confirmSource,
        PositionInfo position,
        boolean matchedLexical,
        Double cosineScore,
        double rrfScore,
        String documentDigitizationStatus,
        List<SourceReferenceView> sourceReferences) {
}
