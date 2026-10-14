package com.aismartstore.backend.multiinstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.aismartstore.backend.upload.UploadCleanupJob;
import com.aismartstore.backend.upload.UploadSessionService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

/** backend-multi-instance Task 4/5 (AC-6, AC-7): a held lock skips the tick; the shedlock row is written. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"app.scheduling.enabled=false"})
class SchedulerLockIT extends MultiInstanceITBase {

    @MockBean
    private UploadSessionService uploadSessionService;

    @Autowired
    private UploadCleanupJob job;

    @Autowired
    private LockProvider lockProvider;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void heldLockSkipsTheJobBodyAndFreeLockRunsItThenBlocksTheNextInstanceWithinLockAtLeastFor() {
        Optional<SimpleLock> held = lockProvider.lock(
                new LockConfiguration(Instant.now(), "upload-cleanup", Duration.ofMinutes(5), Duration.ZERO));
        assertThat(held).isPresent();

        job.run(); // another "instance" holds the lock
        verify(uploadSessionService, never()).findExpiredSessionIds(org.mockito.ArgumentMatchers.anyInt());

        held.get().unlock();
        job.run(); // this instance wins
        verify(uploadSessionService, times(1)).findExpiredSessionIds(org.mockito.ArgumentMatchers.anyInt());

        job.run(); // lockAtLeastFor (30s) not elapsed: a late-phase instance must skip
        verify(uploadSessionService, times(1)).findExpiredSessionIds(org.mockito.ArgumentMatchers.anyInt());

        Integer rows = jdbc.queryForObject("SELECT count(*) FROM shedlock WHERE name = 'upload-cleanup'", Integer.class);
        assertThat(rows).isEqualTo(1);
    }
}
