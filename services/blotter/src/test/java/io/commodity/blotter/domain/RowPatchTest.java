package io.commodity.blotter.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.commodity.blotter.domain.RowChange.PatchOp;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure unit tests of the delta computation and coalescing: no Spring, no database. */
class RowPatchTest {

    private static RowView row(String priced, String unpriced, String approval, String valuation) {
        return new RowView("1.1.1", "1", "1.1", "DESK-1", "BULK", "Coal", LocalDate.of(2026, 9, 18), "100.0000", priced, unpriced, false, approval, "ACTIVE",
                valuation, valuation == null ? null : LocalDate.of(2026, 9, 18), valuation == null ? null : "ASSIGNMENT", valuation == null ? null : "MODERN", null);
    }

    // PROVES the delta is minimal: only the fields that changed are sent.
    @Test
    void diffSendsOnlyTheChangedFields() {
        RowChange change = RowPatch.diff(row("0.0000", "100.0000", "UNAPPROVED", null), row("30.0000", "70.0000", "UNAPPROVED", null));

        assertThat(change.ops()).extracting(PatchOp::op).containsOnly("replace");
        assertThat(change.ops()).extracting(PatchOp::path).containsExactly("/pricedQty", "/unpricedQty");
        assertThat(change.ops()).extracting(PatchOp::value).containsExactly("30.0000", "70.0000");
    }

    @Test
    void anUnchangedRowProducesAnEmptyPatch() {
        assertThat(RowPatch.diff(row("0.0000", "100.0000", "UNAPPROVED", null), row("0.0000", "100.0000", "UNAPPROVED", null)).isEmpty()).isTrue();
    }

    @Test
    void aNewRowIsAnAddOfTheWholeRowAndARemovedRowIsARemove() {
        RowChange added = RowPatch.diff(null, row("0.0000", "100.0000", "UNAPPROVED", null));
        assertThat(added.ops()).hasSize(1);
        assertThat(added.ops().get(0).op()).isEqualTo("add");
        assertThat(added.ops().get(0).path()).isEmpty();
        assertThat(((Map<?, ?>) added.ops().get(0).value()).get("assignmentRef")).isEqualTo("1.1.1");

        RowChange removed = RowPatch.diff(row("0.0000", "100.0000", "UNAPPROVED", null), null);
        assertThat(removed.ops()).extracting(PatchOp::op).containsExactly("remove");
    }

    /** Applies a patch to a row-as-map, the way a client does. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> apply(Map<String, Object> current, RowChange change) {
        Map<String, Object> row = current == null ? null : new LinkedHashMap<>(current);
        for (PatchOp op : change.ops()) {
            switch (op.op()) {
                case "add" -> row = new LinkedHashMap<>((Map<String, Object>) op.value());
                case "remove" -> row = null;
                case "replace" -> row.put(op.path().substring(1), op.value());
                default -> throw new IllegalStateException(op.op());
            }
        }
        return row;
    }

    // PROVES the coalescing claim: a row that changed several times in the window is sent once, and applying that ONE merged patch gives exactly the
    // same row as applying every change in order.
    @Test
    void mergedPatchesAreEquivalentToTheSequence() {
        RowView v0 = row("0.0000", "100.0000", "UNAPPROVED", null);
        RowView v1 = row("10.0000", "90.0000", "UNAPPROVED", null);
        RowView v2 = row("10.0000", "90.0000", "APPROVED", null);
        RowView v3 = row("40.0000", "60.0000", "APPROVED", "4000.0000");
        RowChange c1 = RowPatch.diff(v0, v1), c2 = RowPatch.diff(v1, v2), c3 = RowPatch.diff(v2, v3);

        RowChange merged = RowPatch.merge(RowPatch.merge(c1, c2), c3);
        Map<String, Object> viaSequence = apply(apply(apply(v0.fields(), c1), c2), c3);
        Map<String, Object> viaMerged = apply(v0.fields(), merged);

        assertThat(viaMerged).isEqualTo(viaSequence).isEqualTo(v3.fields());
        assertThat(merged.ops().stream().map(PatchOp::path)).doesNotHaveDuplicates(); // one op per field, last value wins
    }

    @Test
    void anAddFollowedByReplacesStaysASingleAddWithTheLatestValues() {
        RowView v0 = row("0.0000", "100.0000", "UNAPPROVED", null);
        RowView v1 = row("25.0000", "75.0000", "UNAPPROVED", null);
        RowChange merged = RowPatch.merge(RowPatch.diff(null, v0), RowPatch.diff(v0, v1));

        assertThat(merged.ops()).hasSize(1);
        assertThat(merged.ops().get(0).op()).isEqualTo("add");
        assertThat(apply(null, merged)).isEqualTo(v1.fields());
    }

    @Test
    void aRemoveAfterChangesWinsAndAReAddAfterARemoveWins() {
        RowView v0 = row("0.0000", "100.0000", "UNAPPROVED", null);
        RowView v1 = row("25.0000", "75.0000", "UNAPPROVED", null);
        assertThat(RowPatch.merge(RowPatch.diff(v0, v1), RowPatch.diff(v1, null)).ops()).extracting(PatchOp::op).containsExactly("remove");
        RowChange readded = RowPatch.merge(RowPatch.diff(v0, null), RowPatch.diff(null, v1));
        assertThat(apply(null, readded)).isEqualTo(v1.fields());
    }

    // PROVES the owner's provisional rule, derived from CURRENT approval: per assignment at assignment level, and for the whole quota at quota level.
    @Test
    void provisionalIsDerivedFromApprovalAtTheRightLevel() {
        RowView valuedApproved = row("0.0000", "100.0000", "APPROVED", "5000.0000");
        RowView valuedUnapproved = new RowView("1.1.2", "1", "1.1", "DESK-1", "BULK", "Coal", LocalDate.of(2026, 9, 18), "50.0000", "0.0000", "50.0000", false,
                "UNAPPROVED", "ACTIVE", "2500.0000", LocalDate.of(2026, 9, 18), "ASSIGNMENT", "MODERN", null);
        RowView noValuation = new RowView("1.1.3", "1", "1.1", "DESK-1", "BULK", "Coal", LocalDate.of(2026, 9, 18), "50.0000", "0.0000", "50.0000", false,
                "UNAPPROVED", "ACTIVE", null, null, null, null, null);

        var assignmentLevel = Provisional.apply(List.of(valuedApproved, valuedUnapproved, noValuation));
        assertThat(assignmentLevel).extracting(RowView::provisional).containsExactly(false, true, null);

        // RM style: one quota-level number shown on every row; provisional while ANY assignment of the quota is unapproved
        RowView q1 = quotaLevel("1.2.1", "APPROVED"), q2 = quotaLevel("1.2.2", "UNAPPROVED");
        assertThat(Provisional.apply(List.of(q1, q2))).extracting(RowView::provisional).containsExactly(true, true);
        assertThat(Provisional.apply(List.of(q1, quotaLevel("1.2.2", "APPROVED")))).extracting(RowView::provisional).containsExactly(false, false);
    }

    private static RowView quotaLevel(String ref, String approval) {
        return new RowView(ref, "1", "1.2", "DESK-1", "RM", "Copper cathode", LocalDate.of(2026, 9, 18), "50.0000", "0.0000", "50.0000", false, approval, "ACTIVE",
                "9000.0000", LocalDate.of(2026, 9, 18), "QUOTA", "LEGACY", null);
    }
}
