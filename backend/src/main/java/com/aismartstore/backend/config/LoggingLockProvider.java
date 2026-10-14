package com.aismartstore.backend.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * backend-multi-instance Task 5 (AC-7): decorator that makes a skipped tick visible - INFO log plus the
 * counter {@code scheduler.lock.skipped{job}} whenever another instance holds the lock.
 */
public class LoggingLockProvider implements LockProvider {

    private static final Logger log = LoggerFactory.getLogger(LoggingLockProvider.class);

    private final LockProvider delegate;
    private final MeterRegistry meterRegistry;

    public LoggingLockProvider(LockProvider delegate, MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Optional<SimpleLock> lock(LockConfiguration lockConfiguration) {
        Optional<SimpleLock> lock = delegate.lock(lockConfiguration);
        if (lock.isEmpty()) {
            log.info("job skipped: lock held name={}", lockConfiguration.getName());
            meterRegistry.counter("scheduler.lock.skipped", "job", lockConfiguration.getName()).increment();
        }
        return lock;
    }
}
