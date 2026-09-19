package io.commodity.gateway.domain;

import io.commodity.contracts.valuation.Lane;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Per-lane limits: how many requests may be in flight to the engines at once, and how long one may stay SENT before the watchdog
 * retries it. The defaults are the illustrative figures for one laptop; the point is that the mechanism exists and is configurable.
 * TARGET: limits adapt AIMD-style to observed engine latency, and each lane has its own topic and consumer group.
 */
public record LaneSettings(int maxInFlight, Duration timeout) {

    public static Map<Lane, LaneSettings> defaults() {
        Map<Lane, LaneSettings> m = new EnumMap<>(Lane.class);
        m.put(Lane.INTERACTIVE, new LaneSettings(4, Duration.ofSeconds(5)));
        m.put(Lane.INVOICE, new LaneSettings(4, Duration.ofSeconds(15)));
        m.put(Lane.BULK, new LaneSettings(8, Duration.ofSeconds(60)));
        m.put(Lane.CLOSE, new LaneSettings(8, Duration.ofSeconds(60)));
        return m;
    }

    public LaneSettings {
        if (maxInFlight < 1) throw new IllegalArgumentException("maxInFlight must be >= 1");
    }
}
