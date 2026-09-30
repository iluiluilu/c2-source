package com.aismartstore.backend.audit;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * audit-logging.md §4.5 (Story 4 AC-1): a daily checkpoint of the most
 * recently sealed row, so {@link AuditService#verifyChain} does not have to
 * scan from the first row of the table every time.
 */
@Component
public class AuditCheckpointJob {

    private static final Logger log = LoggerFactory.getLogger(AuditCheckpointJob.class);

    private final JdbcTemplate jdbcTemplate;
    private final AuditAdvisoryLock advisoryLock;

    public AuditCheckpointJob(JdbcTemplate jdbcTemplate, AuditAdvisoryLock advisoryLock) {
        this.jdbcTemplate = jdbcTemplate;
        this.advisoryLock = advisoryLock;
    }

    private record LatestSealed(long id, String hash) {
    }

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void run() {
        if (!advisoryLock.tryLockForTransaction(AuditAdvisoryLock.CHECKPOINT)) {
            return;
        }

        List<LatestSealed> latestSealed = jdbcTemplate.query(
                "SELECT id, hash FROM audit_log WHERE sealed_at IS NOT NULL ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> new LatestSealed(rs.getLong("id"), rs.getString("hash")));
        if (latestSealed.isEmpty()) {
            return; // nothing sealed yet - no-op
        }

        LatestSealed latest = latestSealed.get(0);
        Long lastCheckpointUpToId =
                jdbcTemplate.queryForObject("SELECT MAX(up_to_id) FROM audit_checkpoint", Long.class);
        if (lastCheckpointUpToId != null && latest.id() <= lastCheckpointUpToId) {
            return; // nothing new to checkpoint since the last run
        }

        jdbcTemplate.update(
                "INSERT INTO audit_checkpoint (up_to_id, root_hash) VALUES (?, ?)", latest.id(), latest.hash());
        log.info("AuditCheckpointJob: recorded checkpoint up_to_id={}", latest.id());
    }
}
