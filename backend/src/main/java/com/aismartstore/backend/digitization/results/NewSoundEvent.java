package com.aismartstore.backend.digitization.results;

import java.math.BigDecimal;

/**
 * digitization-results-store.md §3: one raw SED detection for {@link
 * DigitizationRunLifecycleService#writeSoundEvents} - every detection is
 * recorded (US5 AC-1) regardless of {@code confidence}.
 */
public record NewSoundEvent(String label, String labelVi, BigDecimal confidence, int timeStartMs, int timeEndMs) {
}
