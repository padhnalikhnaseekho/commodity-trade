package io.commodity.pricing.service;

import io.commodity.contracts.refs.QuotaRef;
import io.commodity.pricing.domain.AssignmentContent;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The seam that makes structural sharing an implementation detail: one interface, two strategies, chosen by
 * {@code commodity.pricing.revision-strategy} ("structural-sharing" or "copy-all").
 *
 * <p>Contract: given the COMPLETE assignment contents a quota should have in the new revision, persist a new immutable
 * quota revision so that resolving it yields exactly those contents. HOW many rows that takes is the writer's business.
 * Readers cannot tell the strategies apart; the equivalence property test proves it.
 *
 * <p>Must be called inside the caller's transaction, with the quota's advisory lock held (see PricingService).
 */
public interface RevisionWriter {

    PricingQuotaRevision write(QuotaRef quota, UUID qagrId, LocalDate brd, List<AssignmentContent> assignments);

    /** What was written. {@code rowsWritten} counts every inserted row across all pricing tables (the headline metric). */
    record PricingQuotaRevision(UUID pqrId, String quotaRef, UUID qagrId, UUID previousPqrId, LocalDate brd, int rowsWritten) {}
}
