package io.github.jaronie.centroidServer.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The thread pool that runs centroidFinder.jar, kept separate from the pool
 * Tomcat uses to serve requests.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** Bean name, referenced by @Async so there is no ambiguity about which pool runs. */
    public static final String JOB_EXECUTOR = "jobExecutor";

    @Bean(JOB_EXECUTOR)
    public ThreadPoolTaskExecutor jobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Each job is a whole extra JVM doing video decoding, so these numbers
        // are small on purpose. Two at a time, ten more allowed to wait.
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("job-");

        // When all 12 slots are full, refuse new work loudly instead of queueing
        // without limit. The thrown exception becomes our 500 "Error starting job".
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

        return executor;
    }
}
