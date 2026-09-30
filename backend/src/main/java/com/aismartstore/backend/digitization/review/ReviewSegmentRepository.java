package com.aismartstore.backend.digitization.review;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * digitization-review-02-stt.md §4 bước 1 (Task 2): a native query joining
 * {@code content_segment -> digitization_run -> digitization_source}, kept
 * here (not on {@code ContentSegmentRepository}) because it exists purely to
 * serve the review-time STT time-bound validate (§4 bước 1) - not part of
 * the results-store's internal API ({@code DigitizationRunLifecycleService}).
 */
@Repository
public class ReviewSegmentRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReviewSegmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * digitization-review-04-sed-summary.md §2.2 (Story 10 AC-4) - one row of
     * a {@code video_summary} segment's {@code source_segment_ids}, resolved
     * against the current state of its source {@link
     * com.aismartstore.backend.digitization.results.ContentSegment}/{@link
     * com.aismartstore.backend.digitization.results.DigitizationRun}.
     * {@code sourceSegmentUpdatedAt}/{@code sourceRunUpdatedAt} are internal
     * inputs to §2.3's {@code staleWarning} formula - never exposed directly
     * (see {@link SourceReferenceView} for the public projection).
     */
    public record SourceReferenceRow(
            UUID sourceSegmentId, UUID sourceRunId, int segmentIndex, Integer timeStartMs,
            String sourceRunReviewState, OffsetDateTime sourceSegmentUpdatedAt, OffsetDateTime sourceRunUpdatedAt) {
    }

    private static final RowMapper<SourceReferenceRow> SOURCE_REFERENCE_ROW_MAPPER = (rs, rowNum) ->
            new SourceReferenceRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("source_run_id", UUID.class),
                    rs.getInt("segment_index"),
                    (Integer) rs.getObject("current_time_start_ms"),
                    rs.getString("source_run_review_state"),
                    rs.getObject("source_segment_updated_at", OffsetDateTime.class),
                    rs.getObject("source_run_updated_at", OffsetDateTime.class));

    /**
     * digitization-review-04-sed-summary.md §2.2 - resolve a {@code
     * video_summary} segment's {@code source_segment_ids} (parsed from JSONB
     * at the call site, digitization-results-store.md §1) to their current
     * row. An id that no longer matches any row is simply absent from the
     * result (INNER JOIN) - §2.2 "bỏ qua phần tử đó khi build response,
     * không ném lỗi 500" - the caller must not treat a shorter result list
     * as an error.
     */
    public List<SourceReferenceRow> resolveSourceReferences(List<UUID> sourceSegmentIds) {
        if (sourceSegmentIds == null || sourceSegmentIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(sourceSegmentIds.size(), "?"));
        return jdbcTemplate.query(
                "SELECT src.id, src.segment_index, src.current_time_start_ms, src.run_id AS source_run_id, "
                        + "dr.review_state AS source_run_review_state, "
                        + "src.updated_at AS source_segment_updated_at, dr.updated_at AS source_run_updated_at "
                        + "FROM content_segment src JOIN digitization_run dr ON dr.id = src.run_id "
                        + "WHERE src.id IN (" + placeholders + ")",
                SOURCE_REFERENCE_ROW_MAPPER, sourceSegmentIds.toArray());
    }

    /**
     * §4 bước 1: {@code digitization_source.duration_ms} of the source
     * behind {@code segmentId}'s run.
     *
     * @return the duration, or {@code null} if it has not been measured yet
     *     (worker sets it in {@code completeRunSuccess}, digitization-pipeline-02
     *     §4.5 step 2) - caller must turn this into a 422, not an NPE.
     */
    public Integer findSourceDurationMs(UUID segmentId) {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT ds.duration_ms FROM content_segment cs "
                        + "JOIN digitization_run dr ON dr.id = cs.run_id "
                        + "JOIN digitization_source ds ON ds.id = dr.source_id "
                        + "WHERE cs.id = ?",
                (rs, rowNum) -> (Integer) rs.getObject("duration_ms"),
                segmentId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
