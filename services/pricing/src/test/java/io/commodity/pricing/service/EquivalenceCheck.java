package io.commodity.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.commodity.contracts.refs.AssignmentRef;
import io.commodity.pricing.domain.*;
import io.commodity.pricing.repository.RevisionStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * The equivalence check behind the property test: generate a random quota and a random history of mutations, write the
 * history under a reference strategy (copy-all) and a candidate strategy, and require that every resolution equals both the
 * in-memory model and the other strategy. If this ever fails, the optimisation is wrong and nothing else about it matters.
 *
 * <p>Everything is derived from one {@code long seed}, so any failure is reproducible by re-running that seed.
 * The history is: quota of 5..30 assignments, each with 0..15 parameters and 1..5 price components, then 1..20 mutations
 * (change quantity, add a parameter, add a component, add/remove an assignment, or a no-op revision), each written as a
 * revision whose BRD either stays the same or advances a day (so same-BRD tie-breaks are exercised too).
 */
final class EquivalenceCheck {

    private static final String[] ELEMENTS = {"AG", "AS", "MOISTURE", "PARTICLE_SIZE"};
    private static final String TEMPLATE_QUOTA = "1.1";
    private static final LocalDate START = LocalDate.of(2026, 9, 1);

    private EquivalenceCheck() {}

    /** A generated history: the model state after each step and the BRD each step is written under. */
    record History(List<List<AssignmentContent>> states, List<LocalDate> brds) {}

    static History history(long seed) {
        Random r = new Random(seed);
        int n = 5 + r.nextInt(26);
        List<AssignmentContent> state = new ArrayList<>();
        for (int i = 1; i <= n; i++) state.add(randomAssignment(r, TEMPLATE_QUOTA + "." + i));
        int nextSeq = n + 1;

        List<List<AssignmentContent>> states = new ArrayList<>(List.of(sorted(state)));
        List<LocalDate> brds = new ArrayList<>(List.of(START));
        int mutations = 1 + r.nextInt(20);
        for (int m = 0; m < mutations; m++) {
            int idx = state.isEmpty() ? 0 : r.nextInt(state.size());
            switch (r.nextInt(6)) {
                case 0 -> { if (!state.isEmpty()) state.set(idx, state.get(idx).withQty(BigDecimal.valueOf(1 + r.nextInt(1_000_000), 4))); }
                case 1 -> { if (!state.isEmpty()) state.set(idx, state.get(idx).withParameter(randomParameter(r))); }
                case 2 -> { if (!state.isEmpty()) state.set(idx, state.get(idx).withComponent(randomComponent(r))); }
                case 3 -> state.add(randomAssignment(r, TEMPLATE_QUOTA + "." + nextSeq++));
                case 4 -> { if (state.size() > 1) state.remove(idx); }
                default -> { } // a revision with no content change: everything should be shared
            }
            states.add(sorted(new ArrayList<>(state)));
            brds.add(brds.get(brds.size() - 1).plusDays(r.nextInt(2)));
        }
        return new History(states, brds);
    }

    /** Writes the history under both writers and asserts every resolution equals the model and the other strategy. */
    static void check(long seed, RevisionWriter reference, RevisionWriter candidate) {
        History h = history(seed);
        String refQuota = PricingTestDb.freshQuotaRef(), candQuota = PricingTestDb.freshQuotaRef();
        RevisionStore store = PricingTestDb.store();

        for (int i = 0; i < h.states().size(); i++) {
            var a = PricingTestDb.write(reference, refQuota, h.brds().get(i), rename(h.states().get(i), refQuota));
            var b = PricingTestDb.write(candidate, candQuota, h.brds().get(i), rename(h.states().get(i), candQuota));
            if (i == 0) assertThat(b.rowsWritten()).as("first revision has nothing to share").isEqualTo(a.rowsWritten());
            else assertThat(b.rowsWritten()).as("candidate never writes more rows than copy-all (step " + i + ")").isLessThanOrEqualTo(a.rowsWritten());
        }

        // for every BRD touched: resolve(reference, brd) == resolve(candidate, brd) == the model's last state on that BRD
        for (LocalDate brd : new TreeSet<>(h.brds())) {
            int last = h.brds().lastIndexOf(brd);
            List<AssignmentContent> expected = h.states().get(last);
            var resolvedRef = rename(store.loadAssignments(store.asOf(refQuota, brd).orElseThrow().pqrId()), refQuota, TEMPLATE_QUOTA);
            var resolvedCand = rename(store.loadAssignments(store.asOf(candQuota, brd).orElseThrow().pqrId()), candQuota, TEMPLATE_QUOTA);
            assertThat(resolvedRef).as("reference resolves to the model at " + brd + " (seed " + seed + ")").isEqualTo(expected);
            assertThat(resolvedCand).as("candidate resolves to the model at " + brd + " (seed " + seed + ")").isEqualTo(expected);
            assertThat(resolvedCand).as("strategies agree at " + brd + " (seed " + seed + ")").isEqualTo(resolvedRef);
        }
    }

    // ---- generators and helpers -------------------------------------------------------------------------------------

    private static AssignmentContent randomAssignment(Random r, String ref) {
        List<ParameterContent> params = new ArrayList<>();
        for (int i = r.nextInt(16); i > 0; i--) params.add(randomParameter(r));
        List<PriceComponentContent> comps = new ArrayList<>();
        for (int i = 1 + r.nextInt(5); i > 0; i--) comps.add(randomComponent(r));
        return new AssignmentContent(ref, BigDecimal.valueOf(1 + r.nextInt(1_000_000), 4), params, comps);
    }

    private static ParameterContent randomParameter(Random r) {
        return new ParameterContent(ELEMENTS[r.nextInt(ELEMENTS.length)], BigDecimal.valueOf(r.nextInt(1_000_000), 6));
    }

    private static PriceComponentContent randomComponent(Random r) {
        BigDecimal qty = BigDecimal.valueOf(1 + r.nextInt(10_000), 4);
        boolean provisional = r.nextBoolean();
        return switch (r.nextInt(3)) {
            case 0 -> new PriceComponentContent(ComponentKind.FIXED, qty, BigDecimal.valueOf(r.nextInt(1_000_000), 6), null, null, null, null, provisional);
            case 1 -> new PriceComponentContent(ComponentKind.AVERAGE, qty, null, "INDEX_" + r.nextInt(5),
                    START.plusDays(r.nextInt(30)), START.plusDays(30 + r.nextInt(30)), null, provisional);
            default -> new PriceComponentContent(ComponentKind.FORMULA, qty, null, null, null, null, "A + " + r.nextInt(10) + " - B", provisional);
        };
    }

    private static List<AssignmentContent> sorted(List<AssignmentContent> list) {
        return list.stream().sorted(Comparator.comparing(a -> AssignmentRef.parse(a.assignmentRef()))).toList();
    }

    /** Re-homes template refs ("1.1.k") under another quota ("q.k") so each strategy writes its own quota. */
    private static List<AssignmentContent> rename(List<AssignmentContent> content, String toQuota) {
        return rename(content, TEMPLATE_QUOTA, toQuota);
    }

    /** Replaces the leading quota ref `from` of every assignment ref with `to` (the suffix ".k" is kept). */
    private static List<AssignmentContent> rename(List<AssignmentContent> content, String from, String to) {
        return content.stream().map(a -> new AssignmentContent(to + a.assignmentRef().substring(from.length()), a.qty(),
                a.parameters(), a.components())).toList();
    }
}
