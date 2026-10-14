package com.aismartstore.backend.outbox;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * classification-notes-01-tags.md §4.6 step 3 (Task 9b) - shared for every
 * {@code outbox_event} type (this stage only writes {@code
 * document.classification.changed}; classification-notes-02-notes.md's
 * {@code document.note.changed} reuses this same job rather than adding a
 * second one, mirroring how migration V19 is shared).
 *
 * <p>Same shape as {@link com.aismartstore.backend.upload.UploadCleanupJob}:
 * only deletes rows already {@code processed_at IS NOT NULL} and past the
 * retention window ({@code classification.outbox_retention_days}, default
 * 30) - an unprocessed row (no consumer exists yet at this stage, see {@link
 * OutboxEvent}'s Javadoc) is never deleted, only logged as a backlog warning
 * once it is older than {@link #BACKLOG_WARNING_THRESHOLD_HOURS}, so
 * operations can notice the missing consumer.
 */
@Component
public class OutboxRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxRetentionJob.class);

    /** §4.6 step 3: "đề xuất 24h" backlog warning threshold. */
    private static final long BACKLOG_WARNING_THRESHOLD_HOURS = 24;

    private final OutboxEventRepository outboxEventRepository;
    private final SystemConfigService systemConfigService;

    public OutboxRetentionJob(OutboxEventRepository outboxEventRepository, SystemConfigService systemConfigService) {
        this.outboxEventRepository = outboxEventRepository;
        this.systemConfigService = systemConfigService;
    }

    /**
     * {@code @Transactional} is required here (unlike {@link
     * com.aismartstore.backend.upload.UploadCleanupJob}, which never needed
     * one): {@link OutboxEventRepository#deleteProcessedBefore} is a {@code
     * @Modifying} bulk-delete query, and {@code @Scheduled} methods run with
     * no active transaction by default - without this, every run throws
     * {@code TransactionRequiredException} before the delete (or the backlog
     * check below it) ever executes.
     */
    @Scheduled(fixedDelayString = "${app.outbox.retention-interval-ms:86400000}")
    @SchedulerLock(name = "outbox-retention", lockAtMostFor = "PT24H", lockAtLeastFor = "PT1M")
    @Transactional
    public void run() {
        long startedAt = System.currentTimeMillis();

        long retentionDays = systemConfigService.getLong(SystemConfigDefaults.CLASSIFICATION_OUTBOX_RETENTION_DAYS);
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(retentionDays);
        int deleted = outboxEventRepository.deleteProcessedBefore(cutoff);

        boolean backlogWarning = false;
        Optional<OutboxEvent> oldestUnprocessed = outboxEventRepository.findFirstByProcessedAtIsNullOrderByCreatedAtAsc();
        if (oldestUnprocessed.isPresent()) {
            OffsetDateTime backlogCutoff = OffsetDateTime.now().minusHours(BACKLOG_WARNING_THRESHOLD_HOURS);
            if (oldestUnprocessed.get().getCreatedAt().isBefore(backlogCutoff)) {
                backlogWarning = true;
                log.warn(
                        "outbox_event backlog: oldest unprocessed row (id={}, eventType={}) was created at {} "
                                + "(older than {}h) - no publish/consumer job exists yet at this stage",
                        oldestUnprocessed.get().getId(),
                        oldestUnprocessed.get().getEventType(),
                        oldestUnprocessed.get().getCreatedAt(),
                        BACKLOG_WARNING_THRESHOLD_HOURS);
            }
        }

        log.info(
                "outbox_event retention: deletedProcessed={} retentionDays={} backlogWarning={} durationMs={}",
                deleted,
                retentionDays,
                backlogWarning,
                System.currentTimeMillis() - startedAt);
    }
}
