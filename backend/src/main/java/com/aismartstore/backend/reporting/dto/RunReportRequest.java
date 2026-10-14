package com.aismartstore.backend.reporting.dto;

import com.aismartstore.backend.reporting.ReportParams;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Body of {@code POST /reports/{templateCode}/run}: {@link ReportParams} + optional paging of the paged tables. */
public record RunReportRequest(
        LocalDate from,
        LocalDate to,
        List<UUID> groupIds,
        List<UUID> topicTagIds,
        List<UUID> docTypeTagIds,
        UUID folderId,
        String granularity,
        Integer page,
        Integer size) {

    public ReportParams toParams() {
        return new ReportParams(from, to, groupIds, topicTagIds, docTypeTagIds, folderId, granularity);
    }
}
