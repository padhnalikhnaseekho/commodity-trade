package io.commodity.stubs.bdc;

import io.commodity.contracts.lookup.BusinessDayClock;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * STUB of Business Day Control: one fixed business date per desk, with a way to roll it forward.
 *
 * <p>TARGET: the real service owns the desk state machine (Open, Processing, Extended Processing, Closed, No Longer
 * Open), one open BRD per desk, and blocks cross-desk operations on mismatched BRDs. None of that is modelled here; the
 * demo only needs the chain "desk BRD -> revision BRD" to exist and resolve. Because callers use the
 * {@link BusinessDayClock} port, swapping in the real service in P1 changes no caller.
 *
 * <p>Desks may sit on different dates at the same time: there is deliberately no global "today".
 */
public class InMemoryBusinessDayClock implements BusinessDayClock {

    private final LocalDate defaultBrd;
    private final Map<String, LocalDate> byDesk = new ConcurrentHashMap<>();

    public InMemoryBusinessDayClock(LocalDate defaultBrd) {
        this.defaultBrd = defaultBrd;
    }

    @Override
    public LocalDate currentBrd(String deskId) {
        return byDesk.getOrDefault(deskId, defaultBrd);
    }

    /** Moves the desk to its next calendar day and returns the new BRD. A real desk would also run its close first. */
    public LocalDate rollForward(String deskId) {
        return byDesk.merge(deskId, defaultBrd.plusDays(1), (current, ignored) -> current.plusDays(1));
    }
}
