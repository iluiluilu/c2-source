package com.aismartstore.backend.digitization.review;

import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * digitization-review-01-ocr.md §4 bước 1 (Story 1): {@code GET
 * /digitization-review/queue}. Hand-rolled SQL (not a {@code Specification})
 * because {@code effectiveStatus} is a computed {@code CASE} expression that
 * both filters (WHERE) and needs to be returned per row - a shape
 * {@code JpaSpecificationExecutor} does not support - and the risk-first
 * {@code ORDER BY} (spec's exact expression, reused verbatim below) needs to
 * run in the database, not in Java, to stay usable at the "100.000 run" scale
 * the spec's test plan calls out.
 *
 * <p>The two boundary conditions in the {@code effective_status} {@code CASE}
 * below MUST stay logically identical to {@link ReviewEffectiveStatus#compute}
 * (used by {@code ReviewController} for the single-row {@code RunDetail}
 * path) - there is no shared code between the two because one runs in SQL
 * and the other in Java.
 */
@Repository
public class ReviewQueueRepository {

    /**
     * digitization-review-01-ocr.md §3 + digitization-review-04-sed-summary.md
     * §3 (API contract table, {@code GET /queue}): every task with a review
     * screen - {@code task=null} lists all five (video: digitization-pipeline-05-video-review.md).
     */
    private static final List<String> REVIEWABLE_TASKS = List.of("ocr", "stt", "sed", "video_summary", "video");

    private static final String EFFECTIVE_STATUS_CASE =
            "CASE "
                    + "WHEN r.review_state = 'in_review' AND r.first_edit_at IS NOT NULL "
                    + "     AND r.first_edit_at <= (now() - (? * INTERVAL '1 hour')) THEN 'stale' "
                    + "WHEN r.review_state = 'pending' AND r.review_deadline_at IS NOT NULL "
                    + "     AND r.review_deadline_at <= (now() + (? * INTERVAL '1 hour')) THEN 'upcoming_deadline' "
                    + "ELSE r.review_state "
                    + "END";

    private static final String IS_REPLACING_CONFIRMED_RUN_EXPR =
            "EXISTS (SELECT 1 FROM digitization_run r2 "
                    + "WHERE r2.document_id = r.document_id AND r2.task = r.task "
                    + "AND r2.review_state = 'confirmed')";

    private final JdbcTemplate jdbcTemplate;
    private final SystemConfigService systemConfigService;

    public ReviewQueueRepository(JdbcTemplate jdbcTemplate, SystemConfigService systemConfigService) {
        this.jdbcTemplate = jdbcTemplate;
        this.systemConfigService = systemConfigService;
    }

    /**
     * @param uploaderSub current user's {@code sub} - Quyết định 5 SA: the
     *     queue is always scoped to documents this user uploaded, never
     *     group-shared (unlike {@code BusinessDataScope}).
     * @param task {@code ocr}/{@code stt}/{@code sed}/{@code video_summary},
     *     or {@code null} for all four.
     * @param effectiveStatuses subset of {@code pending, in_review, stale,
     *     upcoming_deadline}, or {@code null}/empty for no filter.
     * @param qualityFlag {@code ok}/{@code poor}, or {@code null} for no filter.
     */
    public RunSummaryPageView search(
            String uploaderSub, String task, List<String> effectiveStatuses, String qualityFlag, int page, int size) {
        long staleHours = systemConfigService.getLong(SystemConfigDefaults.OCR_STT_REVIEW_STALE_HOURS);
        long upcomingDeadlineHours =
                systemConfigService.getLong(SystemConfigDefaults.OCR_STT_REVIEW_UPCOMING_DEADLINE_HOURS);

        List<Object> whereArgs = new ArrayList<>();
        whereArgs.add(staleHours);
        whereArgs.add(upcomingDeadlineHours);

        StringBuilder where = new StringBuilder(
                // §4 bước 7 (Story 8): the queue only ever lists pending/in_review -
                // a confirmed run (even one just superseded by a newer one) never
                // reappears here, regardless of the caller's status filter.
                // d.deleted_at IS NULL: tài liệu đã bị xóa mềm (thùng rác) không được
                // lọt vào hàng chờ kiểm duyệt số hoá nữa (bug: xóa ở kho tài liệu vẫn
                // hiện ở menu số hoá) - cùng nguyên tắc với
                // DigitizationRunRepository#findDistinctDocumentIdByReviewState.
                "r.review_state IN ('pending', 'in_review') AND d.uploader_sub = ? AND d.deleted_at IS NULL");
        whereArgs.add(uploaderSub);

        if (task != null) {
            where.append(" AND r.task = ?");
            whereArgs.add(task);
        } else {
            where.append(" AND r.task IN (").append(inPlaceholders(REVIEWABLE_TASKS.size())).append(")");
            whereArgs.addAll(REVIEWABLE_TASKS);
        }

        if (qualityFlag != null) {
            where.append(" AND r.output_quality_flag = ?");
            whereArgs.add(qualityFlag);
        }

        String cte =
                "WITH q AS ("
                        + "SELECT r.id, r.document_id, d.original_name AS document_name, r.task, r.review_state, "
                        + "r.processing_status, "
                        + EFFECTIVE_STATUS_CASE + " AS effective_status, "
                        + "r.output_quality_flag, r.low_confidence_count, r.segment_count, "
                        + "r.review_deadline_at, r.first_edit_at, "
                        + IS_REPLACING_CONFIRMED_RUN_EXPR + " AS is_replacing_confirmed_run "
                        + "FROM digitization_run r JOIN document d ON d.id = r.document_id "
                        + "WHERE " + where + ")";

        List<Object> statusArgs = new ArrayList<>();
        String statusFilter = "";
        if (effectiveStatuses != null && !effectiveStatuses.isEmpty()) {
            statusFilter = " WHERE effective_status IN (" + inPlaceholders(effectiveStatuses.size()) + ")";
            statusArgs.addAll(effectiveStatuses);
        }

        List<Object> countArgs = new ArrayList<>(whereArgs);
        countArgs.addAll(statusArgs);
        Long total = jdbcTemplate.queryForObject(
                cte + " SELECT count(*) FROM q" + statusFilter, Long.class, countArgs.toArray());
        long totalElements = total != null ? total : 0L;

        List<Object> dataArgs = new ArrayList<>(whereArgs);
        dataArgs.addAll(statusArgs);
        dataArgs.add(size);
        dataArgs.add(page * size);
        // §4 bước 1 (Story 1 AC-6): risk-first ordering, verbatim from the spec.
        List<RunSummaryView> content = jdbcTemplate.query(
                cte + " SELECT * FROM q" + statusFilter
                        + " ORDER BY (CASE WHEN output_quality_flag = 'poor' THEN 0 ELSE 1 END), "
                        + "low_confidence_count DESC, review_deadline_at ASC "
                        + "LIMIT ? OFFSET ?",
                ROW_MAPPER, dataArgs.toArray());

        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new RunSummaryPageView(content, totalElements, page, size, totalPages);
    }

    private static String inPlaceholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static final RowMapper<RunSummaryView> ROW_MAPPER = (rs, rowNum) -> new RunSummaryView(
            rs.getObject("id", UUID.class),
            rs.getObject("document_id", UUID.class),
            rs.getString("document_name"),
            rs.getString("task"),
            rs.getString("review_state"),
            rs.getString("effective_status"),
            rs.getString("output_quality_flag"),
            rs.getInt("low_confidence_count"),
            rs.getInt("segment_count"),
            rs.getObject("review_deadline_at", OffsetDateTime.class),
            rs.getObject("first_edit_at", OffsetDateTime.class),
            rs.getBoolean("is_replacing_confirmed_run"),
            rs.getString("processing_status"));
}
