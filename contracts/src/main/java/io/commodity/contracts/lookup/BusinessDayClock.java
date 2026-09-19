package io.commodity.contracts.lookup;

import java.time.LocalDate;

/**
 * Port for the desk's current business reporting date (BRD), owned by Business Day Control.
 *
 * <p>Why not LocalDate.now(): every layer of the platform pins to the desk's BRD, not wall-clock time; desks can sit
 * on different business dates at the same time, so there is no global "today". P0 supplies an in-memory stub
 * (services/stubs); P1 replaces it with the real service without touching callers.
 */
public interface BusinessDayClock {
    LocalDate currentBrd(String deskId);
}
