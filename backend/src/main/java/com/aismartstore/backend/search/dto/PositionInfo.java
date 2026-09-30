package com.aismartstore.backend.search.dto;

import java.util.List;

/**
 * search-fulltext-01-tim-kiem-co-ban.md §3.2, chốt hình dạng cụ thể ở
 * search-fulltext-03-dinh-vi-ket-qua.md §2/§3 - where a segment lives in its
 * source: page/line/bbox (+ highlight char offsets) for OCR text, time range
 * for audio/video ({@code stt}/{@code sed}), or {@code type = "none"} for a
 * segment with no finer position ({@code note}/{@code title}, FR-25
 * exception). Built by {@link com.aismartstore.backend.search.HighlightMapper}.
 */
public record PositionInfo(
        String type,
        Integer pageNo,
        Integer lineFrom,
        Integer lineTo,
        List<List<Double>> bboxPolygon,
        Integer highlightOffsetStart,
        Integer highlightOffsetEnd,
        Integer timeStartMs,
        Integer timeEndMs) {

    public static final String TYPE_PAGE = "page";
    public static final String TYPE_TIME = "time";
    public static final String TYPE_NONE = "none";
}
