package com.wenjin.support;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Dedicated scheduler so a blocking worker poll cannot starve its own lease heartbeat. */
@Component
public final class AiJobHeartbeatScheduler implements AutoCloseable {

    private final ScheduledThreadPoolExecutor executor;

    public AiJobHeartbeatScheduler(
            @Value("${wenjin.jobs.heartbeat-threads:1}") int threadCount) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(
                    runnable, "ai-job-heartbeat-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        executor = new ScheduledThreadPoolExecutor(Math.max(1, threadCount), threadFactory);
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long intervalMs) {
        long periodMs = Math.max(10L, intervalMs);
        return executor.scheduleAtFixedRate(
                task, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
