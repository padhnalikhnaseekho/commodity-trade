package io.commodity.stubs.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.UUID;

/**
 * The pure "valuation maths" of the stub engine: deterministic, and deliberately trivial (valuation maths is not the point of the demo).
 *
 * <pre>
 *   result = qty x (basePrice + |hash(subjectRef)| % 10)
 * </pre>
 * No randomness: the same request must always produce the same number, or the idempotency test could not assert anything.
 * Failures are deterministic too: a request is "selected" to fail by a hash of its id, so a demo can show retry and dead-lettering on demand.
 */
public final class StubEngine {

    private StubEngine() {}

    /** Value of a request. {@code hashCode()} of a String is specified by the JDK, so it is stable across runs and machines. */
    public static BigDecimal value(BigDecimal qty, String subjectRef, BigDecimal basePrice) {
        int spread = Math.abs(subjectRef.hashCode() % 10);
        return qty.multiply(basePrice.add(BigDecimal.valueOf(spread))).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Whether this attempt should fail. A request is selected when (hash of its id mod 100) &lt; failureRate x 100, and a selected request fails
     * for its first {@code failAttempts} attempts. failAttempts = 1 shows a retry that then succeeds; failAttempts >= 3 shows a request that
     * exhausts its attempts and ends FAILED.
     */
    public static boolean shouldFail(UUID requestId, int attempt, double failureRate, int failAttempts) {
        boolean selected = Math.floorMod(requestId.hashCode(), 100) < Math.round(failureRate * 100);
        return selected && attempt <= failAttempts;
    }

    /** How long the "engine" takes for a lane: lets a demo make bulk slow and interactive fast without any real computation. */
    public static Duration delay(String lane, java.util.Map<String, Long> delaysMs) {
        return Duration.ofMillis(delaysMs.getOrDefault(lane, 100L));
    }
}
