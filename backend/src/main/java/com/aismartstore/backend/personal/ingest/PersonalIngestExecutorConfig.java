package com.aismartstore.backend.personal.ingest;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** personal-to-warehouse.md §4 step 2: dedicated, bounded executor - one run processes its files sequentially. */
@Configuration
public class PersonalIngestExecutorConfig {

    public static final String EXECUTOR_NAME = "personalIngestExecutor";

    @Bean(name = EXECUTOR_NAME)
    public Executor personalIngestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("personal-ingest-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
