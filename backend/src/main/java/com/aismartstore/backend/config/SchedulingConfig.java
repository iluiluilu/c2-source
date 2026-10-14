package com.aismartstore.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * backend-multi-instance Task 1: {@code @EnableScheduling} only when this
 * instance owns background jobs. Role {@code api} sets
 * {@code app.scheduling.enabled=false}; default (property missing) keeps the
 * previous all-in-one behaviour.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
