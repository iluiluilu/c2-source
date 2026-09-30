package com.aismartstore.backend.digitization.results;

import java.math.BigDecimal;
import java.util.List;

/**
 * digitization-results-store.md §3: one row for {@link
 * DigitizationRunLifecycleService#writeSegments} - worker-supplied content
 * for a single {@link ContentSegment}. {@code text}/{@code timeStartMs}/
 * {@code timeEndMs} seed both {@code ai_*} and {@code current_*} (US2 AC-1);
 * fields not relevant to {@code contentSource} are left null (e.g. {@code
 * pageNo}/{@code bboxPolygon} for {@code stt}, {@code timeStartMs}/{@code
 * timeEndMs} for {@code ocr}).
 */
public record NewSegment(
        int segmentIndex,
        String contentSource,
        String origin,
        Integer pageNo,
        List<List<Double>> bboxPolygon,
        Integer readingOrder,
        Integer lineFrom,
        Integer lineTo,
        Integer timeStartMs,
        Integer timeEndMs,
        String speakerLabel,
        List<String> sourceSegmentIds,
        String text,
        BigDecimal confidence) {
}
