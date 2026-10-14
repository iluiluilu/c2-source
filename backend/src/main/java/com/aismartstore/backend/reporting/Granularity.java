package com.aismartstore.backend.reporting;

import java.util.Locale;

/** AC-7: {@code day|month|quarter|year}. The enum constant (never user text) feeds {@code date_trunc}. */
public enum Granularity {
    DAY("day", "Ngày"),
    MONTH("month", "Tháng"),
    QUARTER("quarter", "Quý"),
    YEAR("year", "Năm");

    private final String pgUnit;
    private final String label;

    Granularity(String pgUnit, String label) {
        this.pgUnit = pgUnit;
        this.label = label;
    }

    /** Vietnamese label shown to users (report filter line, exported files); the wire/pg value stays English. */
    public String label() {
        return label;
    }

    public String pgUnit() {
        return pgUnit;
    }

    public String wire() {
        return pgUnit;
    }

    /** Case-insensitive; {@code null} when not one of the four allowed values. */
    public static Granularity parse(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        for (Granularity g : values()) {
            if (g.pgUnit.equals(v)) {
                return g;
            }
        }
        return null;
    }
}
