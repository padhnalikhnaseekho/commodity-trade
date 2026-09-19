package io.commodity.contracts.events;

import io.commodity.contracts.valuation.SubjectLevel;
import io.commodity.contracts.valuation.ValuationEngine;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published on {@code valuation.published.v1} (key: subjectRef) by the gateway when a valuation completes: the result fan-out for consumers such as the
 * blotter (and later invoicing, accounting and close of books).
 *
 * <p>DEVIATION from the spec, recorded in the addendum: the spec has PRICING publish this ({@code pricing.valuation.published.v1}). In this build pricing
 * does not consume valuation replies, and the gateway is the system that holds the results, so the gateway publishes them. Whether an answer is
 * provisional is NOT in the event: it depends on the approval state, which changes independently of the number, so consumers derive it from the current
 * approval state at read time.
 */
public record ValuationPublished(UUID requestId, String requestKey, String subjectRef, SubjectLevel level, LocalDate brd, ValuationEngine engine,
                                 String value, Instant completedAt) {}
