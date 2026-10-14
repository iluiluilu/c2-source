package com.aismartstore.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/** Quy ước chung: ICT dates, half-open [from 00:00, to+1 00:00) in UTC (Vietnam has no DST). */
class PeriodTest {

    @Test
    void convertsIctMidnightsToUtcHalfOpen() {
        Period p = new Period(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        assertThat(p.start()).isEqualTo(OffsetDateTime.parse("2026-02-28T17:00:00Z"));
        assertThat(p.endExclusive()).isEqualTo(OffsetDateTime.parse("2026-03-31T17:00:00Z"));
    }

    @Test
    void singleDayPeriodSpansExactly24Hours() {
        Period p = new Period(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 15));
        assertThat(java.time.Duration.between(p.start(), p.endExclusive())).hasHours(24);
    }

    @Test
    void lastDayOfMonthAndYearBoundariesRollOverCorrectly() {
        Period feb = new Period(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29)); // leap year
        assertThat(feb.endExclusive()).isEqualTo(OffsetDateTime.parse("2024-02-29T17:00:00Z"));
        Period year = new Period(LocalDate.of(2025, 12, 31), LocalDate.of(2025, 12, 31));
        assertThat(year.endExclusive()).isEqualTo(OffsetDateTime.parse("2025-12-31T17:00:00Z"));
    }

    @Test
    void documentAtExactlyMidnightIctBelongsToTheNewDay() {
        Period march = new Period(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        OffsetDateTime atMidnightIct = OffsetDateTime.parse("2026-02-28T17:00:00Z");
        assertThat(atMidnightIct.isBefore(march.start())).isFalse(); // in the period
        assertThat(atMidnightIct.minusSeconds(1).isBefore(march.start())).isTrue(); // 23:59:59 the day before is not
    }
}
