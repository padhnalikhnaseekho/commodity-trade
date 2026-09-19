package io.commodity.pricing.service;

import io.commodity.contracts.refs.QuotaRef;
import io.commodity.pricing.domain.AssignmentContent;
import io.commodity.pricing.domain.ContentHasher;
import io.commodity.pricing.repository.RevisionStore;
import io.commodity.pricing.repository.RevisionStore.AssignmentRevisionInsert;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The LEGACY behaviour, kept as the baseline: every revision re-versions the WHOLE quota subtree.
 *
 * <p>For every assignment it inserts a new assignment revision, a parameter revision per parameter and a price component
 * per component, then links them all to the new quota revision. Change one assignment out of twenty and you still write all
 * twenty subtrees: hundreds of rows to record a change that touched one. That is the write amplification (revision
 * volume, not trade volume, sizes the system) that {@link StructuralSharingRevisionWriter} removes.
 *
 * <p>Kept in the codebase on purpose: it is the control in the benchmark and in the equivalence property, and the answer to
 * "how do you know your optimisation did not change behaviour?".
 */
public class CopyAllRevisionWriter implements RevisionWriter {

    private final RevisionStore store;

    public CopyAllRevisionWriter(RevisionStore store) {
        this.store = store;
    }

    @Override
    public PricingQuotaRevision write(QuotaRef quota, UUID qagrId, LocalDate brd, List<AssignmentContent> assignments) {
        UUID previous = store.head(quota.toString()).map(RevisionStore.QuotaRevisionRow::pqrId).orElse(null);
        UUID pqrId = UUID.randomUUID();
        int rows = store.insertQuotaRevision(pqrId, quota.toString(), qagrId, previous, brd);

        List<AssignmentRevisionInsert> inserts = new ArrayList<>();
        for (AssignmentContent a : assignments) {
            inserts.add(new AssignmentRevisionInsert(UUID.randomUUID(), a, ContentHasher.hash(a))); // always a new row, changed or not
        }
        rows += store.insertAssignmentRevisions(inserts);
        rows += store.insertMembers(pqrId, inserts.stream().map(AssignmentRevisionInsert::parId).toList());
        return new PricingQuotaRevision(pqrId, quota.toString(), qagrId, previous, brd, rows);
    }
}
