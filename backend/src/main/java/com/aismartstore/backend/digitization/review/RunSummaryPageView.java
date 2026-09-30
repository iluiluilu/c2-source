package com.aismartstore.backend.digitization.review;

import java.util.List;

/**
 * digitization-review-01-ocr.md §3 ({@code GET /digitization-review/queue}
 * response, {@code Page<RunSummary>}) - explicit page DTO, same rationale as
 * {@code AuditController.AuditPage}. {@link ReviewQueueRepository} computes
 * {@code totalElements}/{@code totalPages} itself (a plain {@code
 * COUNT(*)} query alongside the paginated one), so this is a thin wrapper,
 * not a {@code Page<RunSummaryView>} adapter.
 */
public record RunSummaryPageView(
        List<RunSummaryView> content, long totalElements, int page, int size, int totalPages) {
}
