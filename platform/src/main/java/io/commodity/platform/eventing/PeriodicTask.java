package io.commodity.platform.eventing;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs a task on a fixed delay for the life of the application (outbox polling, dedup sweeps, watchdogs).
 *
 * <p>WHY a SmartLifecycle and not @Scheduled: it needs no global @EnableScheduling, and each component starts and stops its own
 * task. fixedDelay (not fixedRate) so a slow run never overlaps the next. A failing run is logged and the schedule survives.
 */
public class PeriodicTask implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PeriodicTask.class);

    private final Runnable task;
    private final long periodMillis;
    private final String name;
    private ScheduledExecutorService executor;

    public PeriodicTask(String name, long periodMillis, Runnable task) {
        this.name = name;
        this.periodMillis = periodMillis;
        this.task = task;
    }

    @Override
    public synchronized void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, name));
        executor.scheduleWithFixedDelay(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("{} failed; will retry", name, e);
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) executor.shutdown();
        executor = null;
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
