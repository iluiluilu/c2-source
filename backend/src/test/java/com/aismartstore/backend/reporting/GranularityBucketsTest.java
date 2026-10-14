package com.aismartstore.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Q5: bucket counting for the {@code reporting.max_buckets} guard (default 400), and the labels/starts used by Bảng 3. */
class GranularityBucketsTest {

    @Test
    void exactly400DailyBucketsIsAllowedAnd401IsNot() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        Period p400 = new Period(from, from.plusDays(399));
        Period p401 = new Period(from, from.plusDays(400));
        assertThat(p400.bucketCount(Granularity.DAY)).isEqualTo(400);
        assertThat(p401.bucketCount(Granularity.DAY)).isEqualTo(401);
        long max = 400;
        assertThat(p400.bucketCount(Granularity.DAY) > max).isFalse();
        assertThat(p401.bucketCount(Granularity.DAY) > max).isTrue();
    }

    @Test
    void monthQuarterYearCountPartialBucketsAtBothEnds() {
        Period p = new Period(LocalDate.of(2025, 11, 20), LocalDate.of(2026, 2, 10));
        assertThat(p.bucketCount(Granularity.MONTH)).isEqualTo(4); // Nov, Dec, Jan, Feb
        assertThat(p.bucketCount(Granularity.QUARTER)).isEqualTo(2); // Q4-2025, Q1-2026
        assertThat(p.bucketCount(Granularity.YEAR)).isEqualTo(2);
        assertThat(new Period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)).bucketCount(Granularity.YEAR)).isEqualTo(1);
    }

    @Test
    void parsesOnlyTheFourAllowedValuesCaseInsensitively() {
        assertThat(Granularity.parse("Day")).isEqualTo(Granularity.DAY);
        assertThat(Granularity.parse(" quarter ")).isEqualTo(Granularity.QUARTER);
        assertThat(Granularity.parse("week")).isNull();
        assertThat(Granularity.parse("")).isNull();
        assertThat(Granularity.parse(null)).isNull();
        assertThat(Granularity.values()).hasSize(4);
    }

    @Test
    void bucketStartsAndLabelsAreContinuousIncludingEmptyBuckets() {
        Period p = new Period(LocalDate.of(2025, 11, 20), LocalDate.of(2026, 2, 10));
        List<LocalDateTime> months = ReportQueryService.bucketStarts(p, Granularity.MONTH);
        assertThat(months).hasSize(4).first().isEqualTo(LocalDate.of(2025, 11, 1).atStartOfDay());
        assertThat(months.stream().map(m -> ReportQueryService.bucketLabel(m, Granularity.MONTH)).toList())
                .containsExactly("2025-11", "2025-12", "2026-01", "2026-02");
        assertThat(ReportQueryService.bucketStarts(p, Granularity.QUARTER).stream()
                        .map(m -> ReportQueryService.bucketLabel(m, Granularity.QUARTER)).toList())
                .containsExactly("2025-Q4", "2026-Q1");
        assertThat(ReportQueryService.bucketStarts(p, Granularity.YEAR).stream()
                        .map(m -> ReportQueryService.bucketLabel(m, Granularity.YEAR)).toList())
                .containsExactly("2025", "2026");
        assertThat(ReportQueryService.bucketStarts(new Period(LocalDate.of(2026, 3, 30), LocalDate.of(2026, 4, 2)), Granularity.DAY))
                .hasSize(4);
    }
}
