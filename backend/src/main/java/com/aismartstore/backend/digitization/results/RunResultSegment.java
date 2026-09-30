package com.aismartstore.backend.digitization.results;

import java.math.BigDecimal;
import java.util.List;

/**
 * digitization-pipeline-02-ocr-stt-processing.md §3.3 - one segment of a
 * successful {@code q.result} message, already parsed off the wire (see
 * {@code com.aismartstore.backend.digitization.results.DigitizationResultConsumer}).
 * OCR-only fields ({@code pageNo}/{@code bboxPolygon}/{@code readingOrder}/
 * {@code lineFrom}/{@code lineTo}) are null for {@code task=stt}; STT-only
 * fields ({@code timeStartMs}/{@code timeEndMs}/{@code speakerLabel}/{@code
 * noSpeechProb}) are null for {@code task=ocr} - same convention as {@link
 * NewSegment}.
 */
public record RunResultSegment(
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
