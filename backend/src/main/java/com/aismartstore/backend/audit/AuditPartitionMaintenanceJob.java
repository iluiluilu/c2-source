package com.aismartstore.backend.audit;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * audit-logging.md §4.5 (Task 8): keeps {@code audit_log} supplied with a
 * partition for next month, monthly, on top of the 3 months migration V21
 * bootstraps. Native SQL via {@link JdbcTemplate} - operational data, not
 * schema versioning, so deliberately outside Flyway (per the spec).
 */
@Component
public class AuditPartitionMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(AuditPartitionMaintenanceJob.class);
    private static final DateTimeFormatter PARTITION_SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");

    private final JdbcTemplate jdbcTemplate;
    private final AuditAdvisoryLock advisoryLock;

    public AuditPartitionMaintenanceJob(JdbcTemplate jdbcTemplate, AuditAdvisoryLock advisoryLock) {
        this.jdbcTemplate = jdbcTemplate;
        this.advisoryLock = advisoryLock;
    }

    @Scheduled(cron = "0 0 3 1 * *")
    @Transactional
    public void run() {
        if (!advisoryLock.tryLockForTransaction(AuditAdvisoryLock.PARTITION_MAINTENANCE)) {
            return;
        }

        YearMonth nextMonth = YearMonth.now().plusMonths(1);
        LocalDate start = nextMonth.atDay(1);
        LocalDate end = nextMonth.plusMonths(1).atDay(1);
        String partitionName = "audit_log_" + start.format(PARTITION_SUFFIX);

        jdbcTemplate.execute(String.format(
                "CREATE TABLE IF NOT EXISTS \"%s\" PARTITION OF audit_log FOR VALUES FROM ('%s') TO ('%s')",
                partitionName, start, end));
        log.info("AuditPartitionMaintenanceJob: ensured partition {} exists ([{}, {}))", partitionName, start, end);
    }
}
