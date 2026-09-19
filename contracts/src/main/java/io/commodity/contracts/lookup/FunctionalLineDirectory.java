package io.commodity.contracts.lookup;

import java.time.LocalDate;

/**
 * Port for the reference data that decides which FunctionalLine (and so which engine) a trade belongs to.
 *
 * <p>Rule (owner decision): by default a trade's line follows a CUTOVER DATE (trades created before it use the legacy line,
 * later ones the modern line), and the trade may carry an explicit OVERRIDE chosen at creation. Only RM has two lines; every other
 * business line has one. TARGET: this is the SRD service, consumed as a local materialised view from a compacted topic, so
 * routing never depends on SRD being reachable. P0 supplies an in-memory stub.
 */
public interface FunctionalLineDirectory {

    /** Resolves the line: the override if given (must be valid for the business line), else by the cutover rule. */
    FunctionalLine resolve(String businessLine, LocalDate tradeCreatedBrd, String override);

    /** Whether {@code name} is a functional line of the business line (used to validate an override at trade creation). */
    boolean isValid(String businessLine, String name);
}
