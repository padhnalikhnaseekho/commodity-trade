package io.commodity.logistics.domain;

import io.commodity.contracts.refs.AssignmentRef;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The difference between two QAG revisions: which assignments were added, removed, or modified (quantity changed).
 *
 * <p>Why it exists: logistics just cut the revision, so it knows what changed. Publishing the answer means no consumer
 * re-derives it (and no two consumers can disagree). Pricing uses added/modified to decide which subtrees to rewrite,
 * which is the input to structural sharing.
 *
 * <p>Worked example: before {1.1.1: 100, 1.1.2: 150}, after {1.1.1: 100, 1.1.2: 200, 1.1.3: 50}
 * gives added [1.1.3], removed [], modified [1.1.2].
 *
 * <p>Refs are returned in natural numeric order (1.1.2 before 1.1.10), so the diff is deterministic. Quantities compare
 * by value, not scale (100 equals 100.0000). "Modified" currently means quantity changed, because quantity is the only
 * per-assignment attribute a revision records.
 *
 * <p>Plain Java, no Spring, no database (QagDiffTest).
 */
public record QagDiff(List<String> added, List<String> removed, List<String> modified) {

    public static QagDiff between(Map<String, BigDecimal> before, Map<String, BigDecimal> after) {
        TreeSet<AssignmentRef> added = new TreeSet<>();
        TreeSet<AssignmentRef> removed = new TreeSet<>();
        TreeSet<AssignmentRef> modified = new TreeSet<>();
        for (var e : after.entrySet()) {
            BigDecimal old = before.get(e.getKey());
            if (old == null) added.add(AssignmentRef.parse(e.getKey()));
            else if (old.compareTo(e.getValue()) != 0) modified.add(AssignmentRef.parse(e.getKey()));
        }
        for (String ref : before.keySet()) {
            if (!after.containsKey(ref)) removed.add(AssignmentRef.parse(ref));
        }
        return new QagDiff(strings(added), strings(removed), strings(modified));
    }

    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && modified.isEmpty();
    }

    private static List<String> strings(Iterable<AssignmentRef> refs) {
        List<String> out = new ArrayList<>();
        refs.forEach(r -> out.add(r.toString()));
        return out;
    }
}
