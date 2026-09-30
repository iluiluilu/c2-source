package com.aismartstore.backend.common;

import java.sql.SQLException;

/**
 * Classifies a caught {@code DataIntegrityViolationException}'s root cause by
 * SQLSTATE (via the standard {@link SQLException#getSQLState()}, so this
 * doesn't need a compile-time dependency on the Postgres driver, which is
 * runtime-scoped in pom.xml). Java equivalent of the NestJS backend's
 * hand-mapped {@code QueryFailedError.driverError.code} checks (see e.g.
 * warehouse.service.ts, tags.service.ts): {@code 23505} unique_violation and
 * {@code 23503} foreign_key_violation both surface as HTTP 409.
 */
public final class SqlErrors {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private SqlErrors() {
    }

    public static boolean isUniqueViolation(Throwable ex) {
        return hasSqlState(ex, UNIQUE_VIOLATION);
    }

    public static boolean isForeignKeyViolation(Throwable ex) {
        return hasSqlState(ex, FOREIGN_KEY_VIOLATION);
    }

    private static boolean hasSqlState(Throwable ex, String sqlState) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof SQLException sqlEx && sqlState.equals(sqlEx.getSQLState())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
