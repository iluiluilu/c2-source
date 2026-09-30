package com.aismartstore.backend.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * audit-logging.md §4.2 (Task 4): computes the hash chain that {@link
 * AuditService#record} no longer computes synchronously. Ticks every 5s;
 * each tick claims up to 500 unsealed rows (oldest first) and chains them
 * onto whatever the last sealed row's hash was.
 *
 * <p>Deliberately reads/writes via {@link JdbcTemplate} (native SQL), never
 * through the {@code AuditLog} JPA entity: a row loaded and mutated through
 * Hibernate would get flushed as an {@code UPDATE} by dirty checking, and
 * the append-only trigger only allows exactly one such UPDATE per row (the
 * seal itself) - a native, explicit {@code UPDATE ... WHERE hash IS NULL}
 * is both simpler to reason about here and immune to Hibernate ever issuing
 * a second, trigger-rejected UPDATE for the same row.
 */
@Component
public class AuditSealerJob {

    private static final Logger log = LoggerFactory.getLogger(AuditSealerJob.class);
    private static final int BATCH_SIZE = 500;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final AuditAdvisoryLock advisoryLock;

    public AuditSealerJob(JdbcTemplate jdbcTemplate, AuditAdvisoryLock advisoryLock) {
        this.jdbcTemplate = jdbcTemplate;
        this.advisoryLock = advisoryLock;
    }

    private record UnsealedRow(
            long id,
            OffsetDateTime createdAt,
            String actor,
            String actorSub,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            String method,
            String path,
            Integer statusCode,
            String ip,
            Map<String, Object> metadata) {
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void seal() {
        if (!advisoryLock.tryLockForTransaction(AuditAdvisoryLock.SEAL)) {
            return; // another instance is already sealing this tick
        }

        List<UnsealedRow> batch = jdbcTemplate.query(
                "SELECT id, created_at, actor, actor_sub, action, resource_type, resource_id, outcome, method, "
                        + "path, status_code, ip, metadata FROM audit_log WHERE hash IS NULL ORDER BY id ASC LIMIT ?",
                (rs, rowNum) -> {
                    String metadataJson = rs.getString("metadata");
                    Map<String, Object> metadata = readMetadata(metadataJson);
                    return new UnsealedRow(
                            rs.getLong("id"),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getString("actor"),
                            rs.getString("actor_sub"),
                            rs.getString("action"),
                            rs.getString("resource_type"),
                            rs.getString("resource_id"),
                            rs.getString("outcome"),
                            rs.getString("method"),
                            rs.getString("path"),
                            (Integer) rs.getObject("status_code"),
                            rs.getString("ip"),
                            metadata);
                },
                BATCH_SIZE);

        if (batch.isEmpty()) {
            return;
        }

        List<String> lastSealedHash = jdbcTemplate.queryForList(
                "SELECT hash FROM audit_log WHERE sealed_at IS NOT NULL ORDER BY id DESC LIMIT 1", String.class);
        String expectedPrev = lastSealedHash.isEmpty() ? null : lastSealedHash.get(0);

        int sealed = 0;
        for (UnsealedRow row : batch) {
            String hash = AuditService.computeHash(
                    row.createdAt(), row.actor(), row.actorSub(), row.action(), row.resourceType(),
                    row.resourceId(), row.outcome(), row.method(), row.path(), row.statusCode(), row.ip(),
                    row.metadata(), expectedPrev);

            int updated = jdbcTemplate.update(
                    "UPDATE audit_log SET prev_hash = ?, hash = ?, sealed_at = now() WHERE id = ? AND hash IS NULL",
                    expectedPrev, hash, row.id());
            if (updated == 1) {
                sealed++;
                expectedPrev = hash;
            }
            // updated == 0 should not happen while we hold AuditAdvisoryLock.SEAL for the whole
            // transaction (no other instance can be sealing concurrently) - kept only as a defensive,
            // idempotent no-op rather than assuming it can never occur.
        }

        log.debug("AuditSealerJob: sealed {}/{} rows this tick", sealed, batch.size());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMetadata(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse audit_log.metadata as JSON", e);
        }
    }
}
