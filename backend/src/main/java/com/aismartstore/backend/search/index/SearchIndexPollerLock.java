package com.aismartstore.backend.search.index;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * backend-multi-instance Task 12 (AC-16): session-scoped Postgres advisory lock so that only ONE instance runs the
 * outbox poller loop at a time. Unlike {@code AuditAdvisoryLock} (xact-scoped) the poller tick spans many short
 * transactions, so the lock is held on a dedicated connection for the whole tick. If that connection dies, Postgres
 * releases the lock by itself.
 */
@Component
public class SearchIndexPollerLock {

    /** Next to AuditAdvisoryLock keys 4915063-66. */
    public static final long KEY = 4915070L;

    private static final Logger log = LoggerFactory.getLogger(SearchIndexPollerLock.class);

    private final DataSource dataSource;

    public SearchIndexPollerLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Held lock; {@link #close()} unlocks and returns the connection (or destroys it if unlock failed). */
    public static final class Handle implements AutoCloseable {
        private final Connection connection;

        private Handle(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void close() {
            boolean unlocked = false;
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    unlocked = rs.next() && rs.getBoolean(1);
                }
            } catch (SQLException e) {
                log.warn("search index poller advisory unlock failed: {}", e.toString());
            }
            if (unlocked) {
                closeQuietly(connection);
            } else {
                // Never hand a connection that may still hold the lock back to the pool: kill the physical one.
                try {
                    connection.abort(Runnable::run);
                } catch (SQLException | RuntimeException e) {
                    closeQuietly(connection);
                }
            }
        }
    }

    /** @return the held lock, or empty if another instance runs the poller (or the DB is unreachable). */
    public Optional<Handle> tryAcquire() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        Handle handle = new Handle(connection);
                        connection = null;
                        return Optional.of(handle);
                    }
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            log.warn("search index poller advisory lock failed: {}", e.toString());
            return Optional.empty();
        } finally {
            if (connection != null) {
                closeQuietly(connection);
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // nothing more to do
        }
    }
}
