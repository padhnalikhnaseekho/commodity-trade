package io.commodity.pricing.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A quota's pricing as of a revision: the result of resolving a quota revision through its members. This is what readers
 * see, and what the equivalence property compares across write strategies: the optimisation must be invisible here.
 * Assignments are in numeric ref order so equal quotas compare equal.
 */
public record ResolvedQuota(UUID pqrId, String quotaRef, UUID qagrId, LocalDate brd, List<AssignmentContent> assignments) {

    /** The content only, ignoring revision identity: two strategies produce different ids but must produce equal content. */
    public List<AssignmentContent> content() {
        return assignments;
    }
}
