package com.aismartstore.backend.multiinstance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/** Shared checks for the RoleFlags* ITs: is scheduling on, and which listener containers auto-start. */
final class RoleFlagsAssertions {

    private RoleFlagsAssertions() {
    }

    static void assertRole(ApplicationContext context, boolean scheduling, boolean embed, boolean result, boolean heartbeat) {
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class).length)
                .as("scheduling enabled")
                .isEqualTo(scheduling ? 1 : 0);

        RabbitListenerEndpointRegistry registry = context.getBean(RabbitListenerEndpointRegistry.class);
        Collection<MessageListenerContainer> containers = registry.getListenerContainers();
        assertThat(containers).hasSize(3);
        assertThat(autoStartup(containers, "q.embed")).as("q.embed consumer").isEqualTo(embed);
        assertThat(autoStartup(containers, "q.result")).as("q.result consumer").isEqualTo(result);
        assertThat(autoStartup(containers, "q.heartbeat")).as("heartbeat consumer").isEqualTo(heartbeat);
        containers.stream()
                .filter(c -> !c.isAutoStartup())
                .forEach(c -> assertThat(c.isRunning()).as("container not started: " + c).isFalse());
    }

    private static boolean autoStartup(Collection<MessageListenerContainer> containers, String queue) {
        return containers.stream()
                .filter(c -> c instanceof org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer a
                        && java.util.Arrays.asList(a.getQueueNames()).contains(queue))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no listener container for " + queue))
                .isAutoStartup();
    }
}
