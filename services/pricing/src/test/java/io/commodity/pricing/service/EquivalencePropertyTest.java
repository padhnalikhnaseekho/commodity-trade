package io.commodity.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.commodity.pricing.domain.ContentHasher;
import io.commodity.pricing.repository.RevisionStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

/**
 * THE test of this repository: the optimisation must be invisible to readers.
 *
 * <p>Property-based (jqwik): for a random quota of 5..30 assignments with 0..15 parameters and 1..5 price components, apply a
 * random history of 1..20 mutations under both strategies, then assert resolve(copy-all, brd) == resolve(sharing, brd) for every
 * BRD touched (and both equal the in-memory model). Each try is one random seed; jqwik prints the failing seed for replay.
 */
class EquivalencePropertyTest {

    private final RevisionStore store = PricingTestDb.store();

    // PROVES the headline claim: structural sharing changes how many rows are written, never what a reader sees.
    @Property(tries = 40)
    void structuralSharingResolvesIdenticallyToCopyAll(@ForAll long seed) {
        EquivalenceCheck.check(seed, new CopyAllRevisionWriter(store), new StructuralSharingRevisionWriter(store));
    }

    /**
     * A deliberately WRONG sharing writer: it reuses an existing assignment revision by assignment ref alone, ignoring content,
     * so a changed assignment silently keeps its old subtree. It exists only to prove the property has teeth.
     */
    private static final class ReuseByRefOnlyWriter implements RevisionWriter {
        private final RevisionStore store;
        private ReuseByRefOnlyWriter(RevisionStore store) { this.store = store; }

        @Override
        public PricingQuotaRevision write(io.commodity.contracts.refs.QuotaRef quota, UUID qagrId, java.time.LocalDate brd,
                                          List<io.commodity.pricing.domain.AssignmentContent> assignments) {
            UUID previous = store.head(quota.toString()).map(RevisionStore.QuotaRevisionRow::pqrId).orElse(null);
            UUID pqrId = UUID.randomUUID();
            int rows = store.insertQuotaRevision(pqrId, quota.toString(), qagrId, previous, brd);
            Map<String, Map<String, UUID>> existing = store.findByRefs(assignments.stream().map(a -> a.assignmentRef()).toList());
            List<UUID> linked = new ArrayList<>();
            List<RevisionStore.AssignmentRevisionInsert> inserts = new ArrayList<>();
            for (var a : assignments) {
                Map<String, UUID> byHash = existing.getOrDefault(a.assignmentRef(), Map.of());
                if (!byHash.isEmpty()) {
                    linked.add(byHash.values().iterator().next()); // BUG: any old revision of this ref, whatever its content
                } else {
                    UUID par = UUID.randomUUID();
                    inserts.add(new RevisionStore.AssignmentRevisionInsert(par, a, ContentHasher.hash(a)));
                    linked.add(par);
                }
            }
            rows += store.insertAssignmentRevisions(inserts) + store.insertMembers(pqrId, linked);
            return new PricingQuotaRevision(pqrId, quota.toString(), qagrId, previous, brd, rows);
        }
    }

    // PROVES the property can fail: a subtly wrong optimisation is caught by the same check within a handful of seeds.
    @Test
    void theEquivalenceCheckCatchesABrokenSharingWriter() {
        assertThatThrownBy(() -> {
            for (long seed = 1; seed <= 30; seed++) {
                EquivalenceCheck.check(seed, new CopyAllRevisionWriter(store), new ReuseByRefOnlyWriter(store));
            }
        }).isInstanceOf(AssertionError.class);
    }

    @Test
    void theSameSeedAlwaysProducesTheSameHistory() {
        assertThat(EquivalenceCheck.history(42).states()).isEqualTo(EquivalenceCheck.history(42).states());
    }
}
