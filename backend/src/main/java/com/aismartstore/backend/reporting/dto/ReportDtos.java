package com.aismartstore.backend.reporting.dto;

import com.aismartstore.backend.reporting.ReportParams;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request/response shapes of the {@code /reports} API (SPEC §3). */
public final class ReportDtos {

    private ReportDtos() {
    }

    public record ParamView(String name, String type, boolean required) {
    }

    public record TemplateView(String code, String name, String description, List<ParamView> params) {
    }

    public record SaveQueryRequest(String name, String templateCode, ReportParams params) {
    }

    public record RenameQueryRequest(String name) {
    }

    public record SavedQuerySummary(UUID id, String name, String templateCode, OffsetDateTime updatedAt) {
    }

    public record SavedQueryDetail(
            UUID id, String name, String templateCode, ReportParams params, List<String> warnings) {
    }

    public record CreateExportRequest(String templateCode, ReportParams params, String format) {
    }

    public record ExportCreated(UUID exportId, String status) {
    }

    public record ExportView(
            UUID id, String templateCode, String format, String status, OffsetDateTime createdAt,
            OffsetDateTime finishedAt, OffsetDateTime expiresAt, String errorMessage) {
    }

    public record ExportPage(List<ExportView> content, int page, int size, long totalElements, int totalPages) {
    }
}
