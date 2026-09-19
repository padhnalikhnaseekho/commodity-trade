package io.commodity.contracts.valuation;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Port for asking the valuation gateway to value a subject (what a caller such as pricing's replay uses).
 * Returns what the gateway answered: a request that was accepted or is already in flight, or an answer served from its cache.
 * A refusal (a closed BRD, ...) surfaces as an exception carrying the gateway's reason.
 */
public interface ValuationSubmitter {

    Submission submit(String subjectRef, SubjectLevel level, LocalDate brd, Lane lane, boolean restatement, String source);

    /** {@code cached} is true when the gateway answered from its result cache and did not call an engine. */
    record Submission(UUID requestId, String requestKey, String status, boolean cached) {}
}
