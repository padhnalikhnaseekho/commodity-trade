package io.commodity.contracts.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.commodity.contracts.valuation.Lane;
import io.commodity.contracts.valuation.SubjectLevel;
import io.commodity.contracts.valuation.ValuationEngine;
import io.commodity.contracts.valuation.ValuationInputs.ComponentInput;
import io.commodity.contracts.valuation.ValuationInputs.ParameterInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Published on {@code valuation.request.v1} (key: requestId) by the gateway, consumed by the engine adapter.
 *
 * <p>SELF-CONTAINED by design: {@code inputs} carries everything the engine needs, so the adapter performs no lookups against
 * logistics, quality or pricing. That is the one thing this design changes outright from the legacy interface (which read the
 * same sources a second time and could disagree with its caller), and it also makes a retry need nothing from any other service.
 * {@code attempt} is 1 for the first send and grows with each retry.
 */
public record ValuationRequested(UUID requestId, String requestKey, String subjectRef, SubjectLevel subjectLevel, LocalDate brd,
                                 String functionalLine, ValuationEngine engine, Lane lane, int attempt, UUID pqrId, UUID parId,
                                 Inputs inputs) {

    /** Total quantity, and the components and parameters of the subject (flattened across assignments for a quota-level request). */
    public record Inputs(@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal qty, List<ComponentInput> components,
                         List<ParameterInput> parameters) {}

    /** The same request for another send attempt (a retry re-sends the stored request unchanged, only the attempt number moves). */
    public ValuationRequested withAttempt(int newAttempt) {
        return new ValuationRequested(requestId, requestKey, subjectRef, subjectLevel, brd, functionalLine, engine, lane, newAttempt, pqrId, parId, inputs);
    }
}
