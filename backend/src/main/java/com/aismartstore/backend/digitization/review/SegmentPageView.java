package com.aismartstore.backend.digitization.review;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Explicit page DTO (same rationale as {@code AuditController.AuditPage}:
 * Spring's default {@code PageImpl} JSON serialization is unstable) - used
 * for {@code RunDetail.segments} (§3.2).
 */
public record SegmentPageView(List<SegmentView> content, long totalElements, int page, int size, int totalPages) {

    public static SegmentPageView from(Page<SegmentView> page) {
        return new SegmentPageView(
                page.getContent(), page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
    }
}
