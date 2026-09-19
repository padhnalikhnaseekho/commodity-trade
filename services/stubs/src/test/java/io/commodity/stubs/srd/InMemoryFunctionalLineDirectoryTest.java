package io.commodity.stubs.srd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.commodity.contracts.valuation.ValuationEngine;
import io.commodity.platform.error.DomainException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class InMemoryFunctionalLineDirectoryTest {

    private final InMemoryFunctionalLineDirectory srd = new InMemoryFunctionalLineDirectory(LocalDate.of(2026, 9, 1));

    // PROVES the owner rule: RM trades follow the cutover date; the engine switch is data, not code.
    @Test
    void rmTradesFollowTheCutoverDate() {
        var before = srd.resolve("RM", LocalDate.of(2026, 8, 31), null);
        var on = srd.resolve("RM", LocalDate.of(2026, 9, 1), null);

        assertThat(before.name()).isEqualTo("RM_LEGACY");
        assertThat(before.engine()).isEqualTo(ValuationEngine.LEGACY);
        assertThat(on.name()).isEqualTo("RM_MODERN");
        assertThat(on.engine()).isEqualTo(ValuationEngine.MODERN);
    }

    @Test
    void otherBusinessLinesAlwaysUseTheirModernLine() {
        assertThat(srd.resolve("ENERGY", LocalDate.of(2020, 1, 1), null).name()).isEqualTo("ENERGY_MODERN");
        assertThat(srd.resolve("BULK", LocalDate.of(2030, 1, 1), null).engine()).isEqualTo(ValuationEngine.MODERN);
    }

    // PROVES the override: an explicit line wins over the cutover date, but only if it belongs to the business line.
    @Test
    void anExplicitOverrideWinsButMustBelongToTheBusinessLine() {
        assertThat(srd.resolve("RM", LocalDate.of(2026, 9, 18), "RM_LEGACY").engine()).isEqualTo(ValuationEngine.LEGACY);
        assertThat(srd.isValid("RM", "RM_LEGACY")).isTrue();
        assertThat(srd.isValid("BULK", "RM_LEGACY")).isFalse();
        assertThatThrownBy(() -> srd.resolve("BULK", LocalDate.of(2026, 9, 18), "RM_LEGACY"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.status()).isEqualTo(400));
    }
}
