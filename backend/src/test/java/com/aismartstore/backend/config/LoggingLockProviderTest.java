package com.aismartstore.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;

class LoggingLockProviderTest {

    private final LockConfiguration config =
            new LockConfiguration(Instant.now(), "some-job", Duration.ofMinutes(5), Duration.ZERO);

    @Test
    void countsASkippedTickWhenTheLockIsHeld() {
        LockProvider delegate = mock(LockProvider.class);
        when(delegate.lock(config)).thenReturn(Optional.empty());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        Optional<SimpleLock> result = new LoggingLockProvider(delegate, registry).lock(config);

        assertThat(result).isEmpty();
        assertThat(registry.counter("scheduler.lock.skipped", "job", "some-job").count()).isEqualTo(1.0);
    }

    @Test
    void doesNotCountWhenTheLockIsAcquired() {
        LockProvider delegate = mock(LockProvider.class);
        SimpleLock lock = mock(SimpleLock.class);
        when(delegate.lock(config)).thenReturn(Optional.of(lock));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThat(new LoggingLockProvider(delegate, registry).lock(config)).containsSame(lock);
        assertThat(registry.find("scheduler.lock.skipped").counter()).isNull();
    }
}
