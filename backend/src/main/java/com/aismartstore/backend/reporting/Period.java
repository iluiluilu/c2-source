package com.aismartstore.backend.reporting;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Report period (Quy ước chung): {@code from}/{@code to} are dates in {@code Asia/Ho_Chi_Minh};
 * half-open {@code [from 00:00, to+1 00:00)} converted to UTC for the DB.
 */
public record Period(LocalDate from, LocalDate to) {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public OffsetDateTime start() {
        return from.atStartOfDay(ZONE).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    /** Exclusive upper bound = {@code to + 1 day 00:00}. */
    public OffsetDateTime endExclusive() {
        return to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    /** Number of buckets the period spans at {@code g} (inclusive of partial first/last bucket). */
    public long bucketCount(Granularity g) {
        return switch (g) {
            case DAY -> ChronoUnit.DAYS.between(from, to) + 1;
            case MONTH -> ChronoUnit.MONTHS.between(from.withDayOfMonth(1), to.withDayOfMonth(1)) + 1;
            case QUARTER -> {
                long fromQ = from.getYear() * 4L + (from.getMonthValue() - 1) / 3;
                long toQ = to.getYear() * 4L + (to.getMonthValue() - 1) / 3;
                yield toQ - fromQ + 1;
            }
            case YEAR -> to.getYear() - from.getYear() + 1L;
        };
    }
}
