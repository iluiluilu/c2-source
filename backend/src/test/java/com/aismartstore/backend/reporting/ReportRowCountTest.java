package com.aismartstore.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.aismartstore.backend.reporting.dto.ReportResult;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** AC-28 / Part T §3: row_count = sum of totalRows over every table; paging never changes totals. */
class ReportRowCountTest {

    private static ReportResult.Table table(String id, int rows, long totalRows, boolean paged) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            list.add(Map.of("k", i));
        }
        return new ReportResult.Table(id, id, List.of(new ReportResult.Column("k", "k", "number")), list, totalRows, paged);
    }

    private static ReportResult result(ReportResult.Table... tables) {
        return new ReportResult(
                "REPO_SUMMARY", "t", new ReportResult.PeriodView(LocalDate.now(), LocalDate.now()), Map.of(), "n",
                OffsetDateTime.now(), "u", List.of(), List.of(), false, List.of(tables), List.of());
    }

    @Test
    void sumsTotalRowsOfAllTables() {
        assertThat(result(table("a", 3, 3, false), table("b", 4, 4, false)).rowCount()).isEqualTo(7);
        assertThat(result().rowCount()).isZero();
    }

    @Test
    void pagingKeepsTotalsAndCutsOnlyPagedTables() {
        ReportResult full = result(table("a", 3, 3, false), table("m", 10, 10, true));
        ReportResult page = ReportsService.paginate(full, 1, 4);
        assertThat(page.tables().get(0).rows()).hasSize(3); // not paged
        assertThat(page.tables().get(1).rows()).hasSize(4);
        assertThat(page.tables().get(1).rows().get(0)).containsEntry("k", 4);
        assertThat(page.tables().get(1).totalRows()).isEqualTo(10);
        assertThat(page.rowCount()).isEqualTo(full.rowCount());
        // last, partial page and a page beyond the end
        assertThat(ReportsService.paginate(full, 2, 4).tables().get(1).rows()).hasSize(2);
        assertThat(ReportsService.paginate(full, 9, 4).tables().get(1).rows()).isEmpty();
    }
}
