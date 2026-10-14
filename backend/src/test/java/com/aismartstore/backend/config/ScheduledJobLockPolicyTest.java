package com.aismartstore.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * backend-multi-instance Task 4/7 (AC-6, AC-8): every {@code @Scheduled} method must either carry
 * {@code @SchedulerLock} (group S: would run twice if two instances ticked) or be listed below with the mechanism
 * that makes concurrent ticks safe (group C). A new job fails this test until its author decides which group it is in.
 */
class ScheduledJobLockPolicyTest {

    /** Group C: concurrency-safe by construction (claim query / conditional update / advisory lock). */
    private static final Set<String> SAFE_WITHOUT_SHEDLOCK = Set.of(
            "AutoConfirmScheduler#run", // UPDATE ... FOR UPDATE SKIP LOCKED claim
            "ReviewStaleAlertJob#run", // UPDATE stale_alerted conditional + audit in the same tx (SKIP LOCKED)
            "DigitizationRunLeaseReconciliationJob#run", // SELECT ... FOR UPDATE SKIP LOCKED
            "ReportExportJobRunner#poll", // UPDATE ... FOR UPDATE SKIP LOCKED RETURNING
            "AuditPartitionMaintenanceJob#run", // AuditAdvisoryLock (xact)
            "AuditSealerJob#seal", // AuditAdvisoryLock (xact)
            "AuditRetentionJob#run", // AuditAdvisoryLock (xact)
            "AuditCheckpointJob#run", // AuditAdvisoryLock (xact)
            "SearchIndexOutboxPoller#poll"); // session advisory lock SearchIndexPollerLock + SKIP LOCKED claim

    @Test
    void everyScheduledMethodIsLockedOrExplicitlyAllowlisted() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(java.util.regex.Pattern.compile(".*")));

        List<String> violations = new ArrayList<>();
        List<String> seenSafe = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.aismartstore.backend")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            for (Method method : type.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Scheduled.class)) {
                    continue;
                }
                String id = type.getSimpleName() + "#" + method.getName();
                if (method.isAnnotationPresent(SchedulerLock.class)) {
                    continue;
                }
                if (SAFE_WITHOUT_SHEDLOCK.contains(id)) {
                    seenSafe.add(id);
                } else {
                    violations.add(id);
                }
            }
        }
        assertThat(violations)
                .as("@Scheduled methods without @SchedulerLock and not in the group-C allowlist")
                .isEmpty();
        // The allowlist must not rot: every entry still exists as a @Scheduled method.
        assertThat(seenSafe).containsExactlyInAnyOrderElementsOf(SAFE_WITHOUT_SHEDLOCK);
    }
}
