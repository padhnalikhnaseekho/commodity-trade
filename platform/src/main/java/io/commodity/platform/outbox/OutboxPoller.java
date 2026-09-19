package io.commodity.platform.outbox;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs an {@link OutboxPublisher} on a fixed delay for the life of the application.
 *
 * <p>WHY a SmartLifecycle and not @Scheduled: it needs no global @EnableScheduling, and each service starts and stops
 * its own poller with its own outbox. fixedDelay (not fixedRate) so a slow batch never overlaps the next one.
 * TRADEOFF: polling adds up to one poll interval of latency to every event. CDC would remove it; the demo accepts it.
 */
public class OutboxPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxPublisher publisher;
    private final long pollMillis;
    private ScheduledExecutorService executor;

    public OutboxPoller(OutboxPublisher publisher, long pollMillis) {
        this.publisher = publisher;
        this.pollMillis = pollMillis;
    }

    @Override
    public synchronized void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "outbox-poller"));
        executor.scheduleWithFixedDelay(() -> {
            try {
                publisher.publishBatch();
            } catch (RuntimeException e) {
                log.error("outbox poll failed; will retry", e); // never let one failure kill the schedule
            }
        }, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
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
