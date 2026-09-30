package com.aismartstore.backend.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * audit-logging.md §4.2/Task 4: an advisory lock is now the *only* lock left
 * anywhere in the audit path, and it lives entirely in background jobs
 * (never in a request's hot path). Four jobs share this helper (sealer,
 * checkpoint, partition maintenance, retention/archive), each with its own
 * key so they never block each other - only concurrent runs of the *same*
 * job (e.g. two backend instances both ticking the sealer) are serialized.
 *
 * <p>Uses {@code pg_try_advisory_xact_lock} (transaction-scoped, non-
 * blocking), not the spec sketch's session-scoped {@code
 * pg_try_advisory_lock}/{@code pg_advisory_unlock} pair: with a pooled
 * {@link JdbcTemplate}, a separate "unlock" call is not guaranteed to run on
 * the same physical connection that took the lock, which would leave the
 * lock held until that connection happens to close. The xact-scoped variant
 * auto-releases at commit/rollback of the caller's {@code @Transactional}
 * method, sidesteps that entirely, and still never blocks (same "skip this
 * tick if another instance is already running it" behaviour the spec
 * describes) - same pattern {@code PersonalService} already uses for its own
 * advisory lock, just with the non-blocking "try" variant since this runs on
 * a schedule rather than inside a user request.
 */
@Component
public class AuditAdvisoryLock {

    /** Distinct, arbitrary keys - one per job type sharing this helper. */
    public static final long SEAL = 4915063L;
    public static final long CHECKPOINT = 4915064L;
    public static final long PARTITION_MAINTENANCE = 4915065L;
    public static final long RETENTION = 4915066L;

    private final JdbcTemplate jdbcTemplate;

    public AuditAdvisoryLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Must be called from inside the caller's {@code @Transactional} method
     * (same connection for the whole tick) - the lock is released
     * automatically when that transaction commits or rolls back.
     */
    public boolean tryLockForTransaction(long key) {
        Boolean acquired = jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, key);
        return Boolean.TRUE.equals(acquired);
    }
}
