package io.commodity.gateway.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.commodity.contracts.valuation.Lane;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LaneLimiterTest {

    private final LaneLimiter limiter = new LaneLimiter(Map.of(
            Lane.INTERACTIVE, new LaneSettings(2, Duration.ofSeconds(5)),
            Lane.INVOICE, new LaneSettings(2, Duration.ofSeconds(15)),
            Lane.BULK, new LaneSettings(3, Duration.ofSeconds(60)),
            Lane.CLOSE, new LaneSettings(3, Duration.ofSeconds(60))));

    // PROVES the isolation claim at the mechanism level: saturating BULK leaves INTERACTIVE's budget untouched.
    @Test
    void aSaturatedLaneDoesNotConsumeAnotherLanesBudget() {
        for (int i = 0; i < 3; i++) assertThat(limiter.tryAcquire(Lane.BULK)).isTrue();
        assertThat(limiter.tryAcquire(Lane.BULK)).isFalse();     // bulk is full

        assertThat(limiter.free(Lane.INTERACTIVE)).isEqualTo(2);  // interactive is untouched
        assertThat(limiter.tryAcquire(Lane.INTERACTIVE)).isTrue();
    }

    @Test
    void releasingFreesASlotAndCanNeverInflateTheBudget() {
        assertThat(limiter.tryAcquire(Lane.INTERACTIVE)).isTrue();
        limiter.release(Lane.INTERACTIVE);
        limiter.release(Lane.INTERACTIVE); // a duplicate or late release
        limiter.release(Lane.INTERACTIVE);

        assertThat(limiter.free(Lane.INTERACTIVE)).isEqualTo(2); // never more than the maximum
        assertThat(limiter.tryAcquire(Lane.INTERACTIVE)).isTrue();
        assertThat(limiter.tryAcquire(Lane.INTERACTIVE)).isTrue();
        assertThat(limiter.tryAcquire(Lane.INTERACTIVE)).isFalse();
    }

    // PROVES restart safety of the counter: it can be seeded from the durable SENT count instead of starting empty.
    @Test
    void canBeInitialisedFromTheDurableInFlightCount() {
        limiter.initialize(Lane.BULK, 2);
        assertThat(limiter.free(Lane.BULK)).isEqualTo(1);
        limiter.initialize(Lane.INVOICE, 99); // more SENT rows than the (possibly reconfigured) limit: clamp, never negative
        assertThat(limiter.free(Lane.INVOICE)).isZero();
    }
}
