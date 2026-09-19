package io.commodity.contracts.valuation;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Port for assembling a valuation's inputs from pricing as of a business date. Empty when pricing has nothing for the subject.
 *
 * <p>DEVIATION FROM THE TARGET DESIGN, ACCEPTED AS A SIMPLIFICATION (recorded in the spec addendum): the spec's gateway API takes only {subjectRef, level, brd, lane}, so the
 * gateway assembles the inputs through this port (HTTP adapter to pricing). TARGET: the CALLER assembles a complete request and the
 * gateway performs no business lookups at all. What stays true either way, and is what the demo makes visible: the ENGINE ADAPTER
 * receives complete inputs and performs no lookups, and has no dependency on any other service.
 */
public interface ValuationInputsProvider {
    Optional<ValuationInputs> assemble(String subjectRef, SubjectLevel level, LocalDate brd);
}
