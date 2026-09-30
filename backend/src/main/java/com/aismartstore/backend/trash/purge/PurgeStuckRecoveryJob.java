package com.aismartstore.backend.trash.purge;

import com.aismartstore.backend.documents.DocumentRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * delete-restore-trash-02-xoa-cung-xung-dot.md §4.3: recovers a document
 * stuck in {@code purging} - {@code TrashPurgeService#purgeOne}'s own
 * transaction for it crashed/errored partway - back to {@code deleted} so
 * the next {@code trash-purge} tick claims and reprocesses it. Threshold
 * (2h, {@link #STUCK_THRESHOLD}) is a DEV choice (SA left it open, §7 rủi ro
 * 3): a lô's heaviest single step is one SeaweedFS delete call, so 2h is
 * generous even for an unusually slow object store while still bounding how
 * long a document can stay un-restorable (AC-8 blocks restore while {@code
 * purging}).
 */
@Component
public class PurgeStuckRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(PurgeStuckRecoveryJob.class);

    static final Duration STUCK_THRESHOLD = Duration.ofHours(2);

    private final DocumentRepository documentRepository;

    public PurgeStuckRecoveryJob(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Scheduled(fixedDelayString = "${app.trash.purge-recovery-interval-ms:1800000}")
    @SchedulerLock(name = "trash-purge-recovery", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    @Transactional
    public void run() {
        int recovered = documentRepository.recoverStuckPurging(OffsetDateTime.now().minus(STUCK_THRESHOLD));
        if (recovered > 0) {
            log.warn(
                    "trash-purge-recovery: recovered {} document(s) stuck in 'purging' back to 'deleted'",
                    recovered);
        }
    }
}
