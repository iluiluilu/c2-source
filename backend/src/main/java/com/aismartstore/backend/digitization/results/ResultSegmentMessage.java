package com.aismartstore.backend.digitization.results;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/**
 * digitization-pipeline-02-ocr-stt-processing.md §3.3 - one segment of a
 * successful {@code q.result} message, as it arrives on the wire (before
 * conversion to {@link RunResultSegment} in {@link DigitizationResultConsumer}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResultSegmentMessage(
        int segmentIndex,
        String text,
        BigDecimal confidence,
        Integer pageNo,
        List<List<Double>> bboxPolygon,
        Integer readingOrder,
        Integer lineFrom,
        Integer lineTo,
        Integer timeStartMs,
        Integer timeEndMs,
        String speakerLabel,
        BigDecimal noSpeechProb) {
}
