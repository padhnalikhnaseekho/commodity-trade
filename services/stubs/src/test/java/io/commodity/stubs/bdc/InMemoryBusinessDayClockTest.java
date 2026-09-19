package io.commodity.stubs.bdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class InMemoryBusinessDayClockTest {

    private final InMemoryBusinessDayClock clock = new InMemoryBusinessDayClock(LocalDate.of(2026, 9, 18));

    @Test
    void unknownDesksStartOnTheDefaultDate() {
        assertThat(clock.currentBrd("DESK-1")).isEqualTo(LocalDate.of(2026, 9, 18));
    }

    // PROVES: BRD is per desk. Rolling one desk forward leaves the others on their own date (no global "today").
    @Test
    void desksRollIndependently() {
        assertThat(clock.rollForward("DESK-1")).isEqualTo(LocalDate.of(2026, 9, 19));
        assertThat(clock.rollForward("DESK-1")).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(clock.currentBrd("DESK-1")).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(clock.currentBrd("DESK-2")).isEqualTo(LocalDate.of(2026, 9, 18));
    }
}
