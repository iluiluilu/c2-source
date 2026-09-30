package com.aismartstore.backend.digitization.results;

/**
 * digitization-review-01-ocr.md §3.4 - thrown by {@link
 * DigitizationRunLifecycleService#reviseSegment}/{@link
 * DigitizationRunLifecycleService#deleteSegment} when the caller's {@code
 * expectedRevisionNo} (optimistic lock, US2 AC-3) does not match {@code
 * content_segment.current_revision_no}. Carries the segment's current state
 * so {@code com.aismartstore.backend.common.ApiExceptionHandler} can build
 * the {@code 409 { latestRevisionNo, latestText, latestTimeStartMs,
 * latestTimeEndMs }} body from spec §3 without a second DB read.
 */
public class SegmentRevisionConflictException extends RuntimeException {

    private final int latestRevisionNo;
    private final String latestText;
    private final Integer latestTimeStartMs;
    private final Integer latestTimeEndMs;

    public SegmentRevisionConflictException(ContentSegment latest, int expectedRevisionNo) {
        super("content_segment " + latest.getId() + " expectedRevisionNo=" + expectedRevisionNo
                + " but current_revision_no=" + latest.getCurrentRevisionNo());
        this.latestRevisionNo = latest.getCurrentRevisionNo();
        this.latestText = latest.getCurrentText();
        this.latestTimeStartMs = latest.getCurrentTimeStartMs();
        this.latestTimeEndMs = latest.getCurrentTimeEndMs();
    }

    public int getLatestRevisionNo() {
        return latestRevisionNo;
    }

    public String getLatestText() {
        return latestText;
    }

    public Integer getLatestTimeStartMs() {
        return latestTimeStartMs;
    }

    public Integer getLatestTimeEndMs() {
        return latestTimeEndMs;
    }
}
