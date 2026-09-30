package com.aismartstore.backend.trash.purge;

import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * delete-restore-trash-02-xoa-cung-xung-dot.md §4.1: the {@code trash-purge}
 * job (Quyết định 5 SA hard delete, Quyết định 3 SA backup gate). Cron stays
 * at 03:00; {@link net.javacrumbs.shedlock.spring.annotation.SchedulerLock}
 * ensures only one backend instance ever runs one tick, and {@code
 * lockAtMostFor} bounds how long a crashed/hung run can hold the lock before
 * the next instance is allowed to take over.
 */
@Component
public class TrashPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(TrashPurgeJob.class);

    private final TrashPurgeService trashPurgeService;
    private final SystemConfigService systemConfigService;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    public TrashPurgeJob(
            TrashPurgeService trashPurgeService,
            SystemConfigService systemConfigService,
            AuditService auditService,
            JdbcTemplate jdbcTemplate) {
        this.trashPurgeService = trashPurgeService;
        this.systemConfigService = systemConfigService;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "${app.trash.purge-cron:0 0 3 * * *}")
    @SchedulerLock(name = "trash-purge", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void run() {
        if (!backupGateOpen()) {
            log.info("trash-purge: skipped this run - backup gate not satisfied (§4.1)");
            auditService.record(new AuditService.AuditEntry(
                    "system", null, "trash.purge.skipped", "system", null, "skipped", null, null, null, null,
                    Map.of("reason", "backup_gate_not_satisfied")));
            return;
        }
        trashPurgeService.runOnce();
    }

    /**
     * §4.1: mandatory gate - a verified-or-replicating backup for TODAY,
     * outside its own maintenance window, must exist before any lô is
     * processed (Quyết định 3, backup-restore.md). {@code
     * backup.purge_gate_enabled=false} is an explicit operator kill switch
     * that bypasses the gate entirely.
     *
     * <p><b>Fail-closed safety net (§7 rủi ro 1):</b> if the {@code
     * backup_run} table does not exist yet (backup-restore not deployed
     * ahead of this slug, contrary to the planned rollout order) or any
     * other DB error occurs while checking, this returns {@code false}
     * (gate NOT satisfied) rather than throwing - a missing/broken gate
     * check must never accidentally let purge run unchecked.
     */
    private boolean backupGateOpen() {
        if (!systemConfigService.getBoolean(SystemConfigDefaults.BACKUP_PURGE_GATE_ENABLED)) {
            return true;
        }
        try {
            Boolean tableExists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'backup_run')",
                    Boolean.class);
            if (tableExists == null || !tableExists) {
                log.warn("trash-purge: 'backup_run' table does not exist yet - gate fails closed (§7 rủi ro 1)");
                return false;
            }
            Long count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM backup_run WHERE consistency_point_at IS NOT NULL "
                            + "AND status IN ('REPLICATING', 'VERIFIED') AND window_open = false "
                            + "AND consistency_point_at::date = CURRENT_DATE",
                    Long.class);
            return count != null && count > 0;
        } catch (DataAccessException e) {
            log.warn("trash-purge: backup gate check failed - failing closed", e);
            return false;
        }
    }
}
