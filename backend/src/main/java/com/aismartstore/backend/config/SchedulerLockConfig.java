package com.aismartstore.backend.config;

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
 * already lives on {@link com.aismartstore.backend.BackendApplication}, only
 * the lock machinery is new here. Reuses the shedlock table (V30) and the
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
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
