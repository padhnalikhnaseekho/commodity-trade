package io.commodity.stubs.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit tests of the stub's maths and failure selection. */
class StubEngineTest {

    // PROVES determinism: the same request always gives the same number (the idempotency test depends on it).
    @Test
    void theValueIsDeterministicAndFollowsTheDocumentedFormula() {
        BigDecimal base = new BigDecimal("100");
        BigDecimal qty = new BigDecimal("250.0000");
        int spread = Math.abs("1.1.3".hashCode() % 10);

        assertThat(StubEngine.value(qty, "1.1.3", base)).isEqualByComparingTo(qty.multiply(BigDecimal.valueOf(100 + spread)));
        assertThat(StubEngine.value(qty, "1.1.3", base)).isEqualTo(StubEngine.value(qty, "1.1.3", base));
        assertThat(StubEngine.value(qty, "1.1.3", base).scale()).isEqualTo(4);
    }

    @Test
    void failuresAreDeterministicAndOnlyAffectTheConfiguredNumberOfAttempts() {
        UUID id = UUID.randomUUID();
        // rate 1.0 selects every request; failAttempts=1 fails the first attempt only, so a retry succeeds
        assertThat(StubEngine.shouldFail(id, 1, 1.0, 1)).isTrue();
        assertThat(StubEngine.shouldFail(id, 2, 1.0, 1)).isFalse();
        // failAttempts=3 fails every attempt the gateway will make, so the request ends FAILED
        assertThat(StubEngine.shouldFail(id, 3, 1.0, 3)).isTrue();
        // rate 0 never fails
        assertThat(StubEngine.shouldFail(id, 1, 0.0, 99)).isFalse();
        assertThat(StubEngine.shouldFail(id, 1, 0.5, 1)).isEqualTo(StubEngine.shouldFail(id, 1, 0.5, 1));
    }
}
