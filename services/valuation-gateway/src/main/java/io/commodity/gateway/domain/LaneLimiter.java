package io.commodity.gateway.domain;

import io.commodity.contracts.valuation.Lane;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded in-flight count per lane (counting-semaphore semantics, one independent budget per lane).
 *
 * <p>WHY: interactive and bulk traffic are opposite workloads on the same engines. Limiting IN-FLIGHT REQUESTS per lane (a concurrency
 * limit, not a request rate) means a bulk sweep can fill only its own budget and can never exhaust the interactive one: a trader's
 * what-if never queues behind a desk-wide revaluation. A rate limit would not give that: it says how fast, not how many at once.
 *
 * <p>Built on atomic counters rather than java.util.concurrent.Semaphore because two things are needed that a Semaphore cannot do
 * safely: starting from a non-full state after a restart ({@link #initialize}, from the durable SENT count) and refusing to release
 * more permits than were taken (a late or duplicate reply must not inflate the budget).
 *
 * <p>TARGET: the count is derived from the durable request table across instances (or a distributed semaphore); the demo runs one
 * gateway instance, so an in-memory counter seeded from the table at startup is enough.
 */
public class LaneLimiter {

    private final Map<Lane, Integer> max = new EnumMap<>(Lane.class);
    private final Map<Lane, AtomicInteger> inFlight = new EnumMap<>(Lane.class);

    public LaneLimiter(Map<Lane, LaneSettings> settings) {
        for (Lane lane : Lane.values()) {
            max.put(lane, settings.get(lane).maxInFlight());
            inFlight.put(lane, new AtomicInteger());
        }
    }

    /** Sets the current in-flight count of a lane, e.g. from the SENT rows found in the database at startup. */
    public void initialize(Lane lane, int alreadyInFlight) {
        inFlight.get(lane).set(Math.min(alreadyInFlight, max.get(lane)));
    }

    /** Takes one slot if the lane has room. Never blocks. */
    public boolean tryAcquire(Lane lane) {
        AtomicInteger n = inFlight.get(lane);
        while (true) {
            int current = n.get();
            if (current >= max.get(lane)) return false;
            if (n.compareAndSet(current, current + 1)) return true;
        }
    }

    /** Returns one slot. Ignored if the lane holds none, so a duplicate release can never inflate the budget. */
    public void release(Lane lane) {
        inFlight.get(lane).updateAndGet(c -> Math.max(0, c - 1));
    }

    public int inFlight(Lane lane) {
        return inFlight.get(lane).get();
    }

    public int free(Lane lane) {
        return max.get(lane) - inFlight.get(lane).get();
    }
}
