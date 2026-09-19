package io.commodity.pricing.service;

import io.commodity.contracts.refs.QuotaRef;
import io.commodity.pricing.domain.AssignmentContent;
import io.commodity.pricing.domain.ContentHasher;
import io.commodity.pricing.repository.RevisionStore;
import io.commodity.pricing.repository.RevisionStore.AssignmentRevisionInsert;
import java.time.LocalDate;
import java.util.*;

/**
 * Copy-on-write revisions: write new rows only for assignments whose CONTENT changed; point at the existing rows for the
 * rest. The same idea as a Git tree: a new root is cheap because unchanged subtrees are shared, not copied.
 *
 * <p>How: compute each assignment's content hash. If a revision with that (assignment ref, hash) already exists, reuse its
 * par_id; otherwise insert a new one with its children. Then link the new quota revision to ALL of them, new and reused.
 * Sharing is safe because those rows are immutable (insert-only, enforced by a trigger): nobody can change what a
 * reused row means underneath another revision.
 *
 * <p>Worked example (20 assignments, each 10 parameters and 3 components, change one): 1 quota revision + 1 new assignment
 * revision + 10 + 3 children + 20 member links = 35 rows, against 301 for copy-all.
 *
 * <p>TRADEOFF (say it before being asked): reads become joins across revisions, so the indexes are designed, not
 * discovered; and the member links are still one row per assignment per revision, so the saving is large but bounded (about
 * 8.6x here, and 1.0x when everything changes). Purging a revision can no longer delete its children blindly, since other
 * revisions may point at them: archival works on whole quota subtrees, which is why partitioning is by BRD.
 *
 * <p>Correctness does not depend on the diff in the event: hashing decides. The event's diff tells us what to expect but a
 * wrong diff can never produce a wrong revision, because equal content always hashes equal.
 */
public class StructuralSharingRevisionWriter implements RevisionWriter {

    private final RevisionStore store;

    public StructuralSharingRevisionWriter(RevisionStore store) {
        this.store = store;
    }

    @Override
    public PricingQuotaRevision write(QuotaRef quota, UUID qagrId, LocalDate brd, List<AssignmentContent> assignments) {
        UUID previous = store.head(quota.toString()).map(RevisionStore.QuotaRevisionRow::pqrId).orElse(null);
        UUID pqrId = UUID.randomUUID();
        int rows = store.insertQuotaRevision(pqrId, quota.toString(), qagrId, previous, brd);

        // One query finds every existing revision of these assignments (served by the (assignment_ref, content_hash) index).
        Map<String, Map<String, UUID>> existing = store.findByRefs(assignments.stream().map(AssignmentContent::assignmentRef).toList());

        List<UUID> linked = new ArrayList<>();
        List<AssignmentRevisionInsert> inserts = new ArrayList<>();
        Set<String> seenRefs = new HashSet<>();
        for (AssignmentContent a : assignments) {
            if (!seenRefs.add(a.assignmentRef())) throw new IllegalArgumentException("duplicate assignment in revision: " + a.assignmentRef());
            String hash = ContentHasher.hash(a);
            UUID reused = existing.getOrDefault(a.assignmentRef(), Map.of()).get(hash);
            if (reused != null) {
                linked.add(reused); // unchanged: point at the row that already exists
            } else {
                UUID parId = UUID.randomUUID();
                inserts.add(new AssignmentRevisionInsert(parId, a, hash));
                linked.add(parId);
            }
        }
        rows += store.insertAssignmentRevisions(inserts);
        rows += store.insertMembers(pqrId, linked);
        return new PricingQuotaRevision(pqrId, quota.toString(), qagrId, previous, brd, rows);
    }
}
