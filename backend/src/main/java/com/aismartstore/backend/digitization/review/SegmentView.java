package com.aismartstore.backend.digitization.review;

import com.aismartstore.backend.digitization.results.ContentSegment;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * digitization-review-01-ocr.md §3.3 - one {@link ContentSegment}, projected
 * for the review UI (never exposes {@code aiTimeStartMs}/{@code
 * aiTimeEndMs}/{@code sourceSegmentIds}/etc - those are internal-only per
 * §3.4's Javadoc on {@code reviseSegment}). {@code overlapsAdjacent}
 * (digitization-review-02-stt.md §3.2) is only ever {@code true} outside of
 * {@link #of(ContentSegment)} (single-segment PATCH/DELETE/POST responses
 * don't know their neighbours and default to {@code false}); it is computed
 * for {@code GET /runs/{id}} via {@link #listWithOverlap(List)}.
 *
 * <p>digitization-review-04-sed-summary.md §3.2 - {@code soundEventLabel}
 * ({@code content_source='sed'}) and {@code sourceReferences} ({@code
 * content_source='summary'}) are both {@code null} unless resolved by the
 * caller (only {@code GET /runs/{id}} does so today, via {@link
 * #listWithOverlap(List, Map, Map)} - single-segment PATCH/DELETE/POST
 * responses leave them {@code null}, matching every other read-only-derived
 * field those endpoints already omit).
 */
public record SegmentView(
        UUID segmentId,
        int segmentIndex,
        String origin,
        String aiText,
        String currentText,
        int currentRevisionNo,
        boolean isEdited,
        boolean isDeleted,
        BigDecimal confidence,
        Integer pageNo,
        List<List<Double>> bboxPolygon,
        Integer readingOrder,
        Integer timeStartMs,
        Integer timeEndMs,
        boolean overlapsAdjacent,
        String soundEventLabel,
        List<SourceReferenceView> sourceReferences) {

    public static SegmentView of(ContentSegment segment) {
        return of(segment, false, null, null);
    }

    public static SegmentView of(ContentSegment segment, boolean overlapsAdjacent) {
        return of(segment, overlapsAdjacent, null, null);
    }

    public static SegmentView of(
            ContentSegment segment, boolean overlapsAdjacent, String soundEventLabel,
            List<SourceReferenceView> sourceReferences) {
        return new SegmentView(
                segment.getId(),
                segment.getSegmentIndex(),
                segment.getOrigin(),
                segment.getAiText(),
                segment.getCurrentText(),
                segment.getCurrentRevisionNo(),
                segment.isEdited(),
                segment.isDeleted(),
                segment.getConfidence(),
                segment.getPageNo(),
                segment.getBboxPolygon(),
                segment.getReadingOrder(),
                segment.getCurrentTimeStartMs(),
                segment.getCurrentTimeEndMs(),
                overlapsAdjacent,
                soundEventLabel,
                sourceReferences);
    }

    /**
     * digitization-review-02-stt.md §3.2 (US3 AC-5): for each segment in
     * {@code orderedSegments} (already sorted by {@code segment_index},
     * e.g. {@code ContentSegmentRepository.findByRunIdOrderBySegmentIndex}),
     * {@code overlapsAdjacent} is {@code true} iff its
     * {@code [currentTimeStartMs, currentTimeEndMs]} overlaps the segment
     * immediately before or after it in this list - only 2 comparisons per
     * segment, not every pair in the run. Always {@code false} for
     * {@code task=ocr} segments (their time fields are {@code null}).
     */
    public static List<SegmentView> listWithOverlap(List<ContentSegment> orderedSegments) {
        return listWithOverlap(orderedSegments, Map.of(), Map.of());
    }

    /**
     * digitization-review-04-sed-summary.md §3.2/§4 bước 1/bước 4 - same as
     * {@link #listWithOverlap(List)}, plus per-segment enrichment looked up
     * by the caller ({@code ReviewController.getRun}): {@code
     * soundEventLabelsBySegmentId} for {@code task=sed}, {@code
     * sourceReferencesBySegmentId} for {@code task=video_summary}. A segment
     * absent from either map (e.g. every {@code ocr}/{@code stt} segment,
     * both maps always empty for those tasks) simply gets {@code null} for
     * that field.
     */
    public static List<SegmentView> listWithOverlap(
            List<ContentSegment> orderedSegments, Map<UUID, String> soundEventLabelsBySegmentId,
            Map<UUID, List<SourceReferenceView>> sourceReferencesBySegmentId) {
        List<SegmentView> result = new ArrayList<>(orderedSegments.size());
        for (int i = 0; i < orderedSegments.size(); i++) {
            ContentSegment current = orderedSegments.get(i);
            ContentSegment previous = i > 0 ? orderedSegments.get(i - 1) : null;
            ContentSegment next = i < orderedSegments.size() - 1 ? orderedSegments.get(i + 1) : null;
            boolean overlaps = overlapsInTime(current, previous) || overlapsInTime(current, next);
            // current.getId() can be null for a not-yet-persisted segment;
            // Map.of()'s immutable maps (the no-op default, listWithOverlap(List))
            // throw NPE on a null-key lookup, so guard explicitly.
            UUID id = current.getId();
            result.add(SegmentView.of(
                    current, overlaps, id == null ? null : soundEventLabelsBySegmentId.get(id),
                    id == null ? null : sourceReferencesBySegmentId.get(id)));
        }
        return result;
    }

    private static boolean overlapsInTime(ContentSegment a, ContentSegment b) {
        if (b == null) {
            return false;
        }
        Integer aStart = a.getCurrentTimeStartMs();
        Integer aEnd = a.getCurrentTimeEndMs();
        Integer bStart = b.getCurrentTimeStartMs();
        Integer bEnd = b.getCurrentTimeEndMs();
        if (aStart == null || aEnd == null || bStart == null || bEnd == null) {
            return false;
        }
        return Math.max(aStart, bStart) < Math.min(aEnd, bEnd);
    }
}
