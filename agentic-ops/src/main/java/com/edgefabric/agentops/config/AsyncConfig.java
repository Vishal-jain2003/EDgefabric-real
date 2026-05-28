package com.edgefabric.agentops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Bounded thread pool for {@code @Async} observe tasks.
 * Named {@code observeExecutor} — {@link com.edgefabric.agentops.observe.ObserveService}
 * uses this to cap parallelism and prevent thread exhaustion under load.
 */
@Configuration
public class AsyncConfig {

    @Bean("observeExecutor")
    public Executor observeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("observe-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
