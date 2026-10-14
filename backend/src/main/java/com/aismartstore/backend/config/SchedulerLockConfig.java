package com.aismartstore.backend.config;

import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * delete-restore-trash-02-xoa-cung-xung-dot.md Task 1: first ShedLock
 * consumer in this backend ({@code TrashPurgeJob}/{@code
 * PurgeStuckRecoveryJob}, §4.1/§4.3) - {@code @EnableScheduling} itself
 * lives on {@link SchedulingConfig} (conditional on the role flag), only
 * the lock machinery is here. Reuses the shedlock table (V30) and the
 * existing Postgres {@link DataSource} - no new infra.
 *
 * <p>{@code defaultLockAtMostFor} is a safety net only: every {@code
 * @SchedulerLock} usage in this codebase sets its own {@code lockAtMostFor}
 * explicitly (per job's own worst-case duration) - this bound only applies if
 * a future job forgets to.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource, MeterRegistry meterRegistry) {
        LockProvider jdbc = new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
        // backend-multi-instance Task 5: log + count skipped ticks.
        return new LoggingLockProvider(jdbc, meterRegistry);
    }
}
