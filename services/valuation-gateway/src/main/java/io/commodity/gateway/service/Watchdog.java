package io.commodity.gateway.service;

import io.commodity.contracts.valuation.Lane;
import io.commodity.gateway.domain.LaneLimiter;
import io.commodity.gateway.domain.LaneSettings;
import io.commodity.gateway.repository.RequestStore;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sweeps for requests that have been SENT longer than their lane's timeout and retries them, or fails them once the attempt budget is spent.
 *
 * <p>WHY a retry needs nothing from any other service: the stored request is complete and self-contained, so the gateway simply sends it again.
 * (The legacy approach replayed stuck requests from their source systems, which meant re-reading them.) Because the request key is derived from
 * immutable inputs, a duplicate answer is harmless: the same key gives the same result.
 *
 * <p>Every transition is compare-and-set, so a watchdog racing a late reply cannot double-handle a request, and the lane slot is released only
 * by whoever actually made the transition.
 */
public class Watchdog {

    private static final Logger log = LoggerFactory.getLogger(Watchdog.class);

    private final RequestStore store;
    private final LaneLimiter limiter;
    private final Map<Lane, LaneSettings> settings;

    public Watchdog(RequestStore store, LaneLimiter limiter, Map<Lane, LaneSettings> settings) {
        this.store = store;
        this.limiter = limiter;
        this.settings = settings;
    }

    /** One sweep over all lanes. Returns how many requests were retried or failed. */
    public int sweep() {
        int handled = 0;
        for (Lane lane : Lane.values()) {
            Duration timeout = settings.get(lane).timeout();
            for (UUID id : store.timedOut(lane, timeout)) {
                var transition = store.failOrRetry(id, "no reply within " + timeout.toMillis() + " ms", ReplyHandler.MAX_ATTEMPTS);
                if (transition.isPresent()) {
                    limiter.release(transition.get().lane());
                    log.warn("request {} timed out in lane {}: now {}", id, lane, transition.get().status());
                    handled++;
                }
            }
        }
        return handled;
    }
}
