package com.aismartstore.backend.reporting.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * SPEC Part T §3: the JSON shape of {@code POST /reports/{code}/run} and the only input of the
 * renderer (AC-36: data reaches Jasper solely through this aggregated DTO).
 */
public record ReportResult(
        String templateCode,
        String title,
        PeriodView period,
        Map<String, String> filters,
        String scopeNotice,
        OffsetDateTime generatedAt,
        String generatedBy,
        List<String> notes,
        List<String> warnings,
        boolean empty,
        List<Table> tables,
        List<Chart> charts) {

    public record PeriodView(LocalDate from, LocalDate to) {
    }

    /** {@code type}: {@code string|number|bytes|duration|percent}. */
    public record Column(String key, String label, String type) {
    }

    public record Table(
            String id, String title, List<Column> columns, List<Map<String, Object>> rows, long totalRows,
            boolean paged) {
    }

    public record Series(String name, List<Number> values) {
    }

    /** {@code type}: {@code BAR|STACKED_BAR}. */
    public record Chart(String id, String title, String type, List<String> categories, List<Series> series) {
    }

    /** AC-28: row_count = sum of {@code totalRows} over every table. */
    public long rowCount() {
        return tables.stream().mapToLong(Table::totalRows).sum();
    }
}
