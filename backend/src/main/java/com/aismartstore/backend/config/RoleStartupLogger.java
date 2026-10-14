package com.aismartstore.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** backend-multi-instance Task 3 (AC-5): one startup line stating the role and the 3 role flags. */
@Component
public class RoleStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(RoleStartupLogger.class);

    private final String role;
    private final boolean scheduling;
    private final boolean semanticConsumer;
    private final boolean messagingConsumers;
    private final boolean semantic;

    public RoleStartupLogger(
            @Value("${app.role:all}") String role,
            @Value("${app.scheduling.enabled:true}") boolean scheduling,
            @Value("${app.semantic.consumer-enabled:true}") boolean semanticConsumer,
            @Value("${app.messaging.consumers-enabled:true}") boolean messagingConsumers,
            @Value("${app.semantic.enabled:false}") boolean semantic) {
        this.role = role;
        this.scheduling = scheduling;
        this.semanticConsumer = semanticConsumer;
        this.messagingConsumers = messagingConsumers;
        this.semantic = semantic;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("role={} scheduling={} semanticConsumer={} messagingConsumers={} semantic={}",
                role, scheduling, semanticConsumer, messagingConsumers, semantic);
    }
}
