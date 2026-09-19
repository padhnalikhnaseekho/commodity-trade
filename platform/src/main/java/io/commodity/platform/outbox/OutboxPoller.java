package io.commodity.platform.outbox;

import io.commodity.platform.eventing.PeriodicTask;

/**
 * Runs an {@link OutboxPublisher} on a fixed delay for the life of the application.
 * TRADEOFF: polling adds up to one poll interval of latency to every event. CDC would remove it; the demo accepts it.
 */
public class OutboxPoller extends PeriodicTask {
    public OutboxPoller(OutboxPublisher publisher, long pollMillis) {
        super("outbox-poller", pollMillis, publisher::publishBatch);
    }
}
