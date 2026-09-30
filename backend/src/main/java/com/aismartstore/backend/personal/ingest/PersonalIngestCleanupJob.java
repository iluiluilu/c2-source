package com.aismartstore.backend.personal.ingest;

import java.time.OffsetDateTime;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** personal-to-warehouse.md §4 step 4: daily cleanup of {@code personal_ingest_item}. */
@Component
public class PersonalIngestCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(PersonalIngestCleanupJob.class);

    private final PersonalToWarehouseService service;

    public PersonalIngestCleanupJob(PersonalToWarehouseService service) {
        this.service = service;
    }

    @Scheduled(cron = "${personal-ingest.cleanup-cron:0 30 3 * * *}")
    @SchedulerLock(name = "personal-ingest-cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        int deleted = service.purgeOldItems(OffsetDateTime.now());
        log.info("personal-ingest cleanup: deleted {} old item rows", deleted);
    }
}
