package io.commodity.blotter.domain;

import io.commodity.blotter.domain.RowChange.PatchOp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Computes and merges row changes. Plain Java: no Spring, no database (RowPatchTest).
 *
 * <p>{@link #diff} compares the row before and after a change and emits the smallest patch:
 * <pre>
 *   before null  -> add    "" {whole row}        (new to the view)
 *   after  null  -> remove ""                    (left the view)
 *   otherwise    -> replace "/pricedQty" "70.0000", ... for each changed field only
 * </pre>
 * {@link #merge} folds two consecutive changes of the SAME row into one, which is what makes coalescing safe: a row that changed nine times in a
 * second is sent once, carrying the last value of each field, and applying the merged patch gives exactly the same row as applying all nine in order.
 */
public final class RowPatch {

    private RowPatch() {}

    public static RowChange diff(RowView before, RowView after) {
        String ref = (after != null ? after : before).assignmentRef();
        String desk = (after != null ? after : before).deskId();
        if (before == null && after == null) return new RowChange(ref, desk, List.of());
        if (before == null) return new RowChange(ref, desk, List.of(new PatchOp("add", "", after.fields())));
        if (after == null) return new RowChange(ref, desk, List.of(new PatchOp("remove", "", null)));

        List<PatchOp> ops = new ArrayList<>();
        Map<String, Object> b = before.fields(), a = after.fields();
        a.forEach((field, value) -> {
            if (!Objects.equals(b.get(field), value)) ops.add(new PatchOp("replace", "/" + field, value));
        });
        return new RowChange(ref, desk, ops);
    }

    /** Folds {@code later} into {@code earlier} (both for the same row); the result is equivalent to applying them in order. */
    public static RowChange merge(RowChange earlier, RowChange later) {
        if (later.ops().stream().anyMatch(o -> o.op().equals("remove") && o.path().isEmpty())) return later;   // gone: nothing before matters
        if (later.ops().stream().anyMatch(o -> o.op().equals("add") && o.path().isEmpty())) return later;      // (re)appeared: the whole row wins

        boolean earlierIsAdd = earlier.ops().stream().anyMatch(o -> o.op().equals("add") && o.path().isEmpty());
        if (earlierIsAdd) { // a replace on top of an add: fold the replaced fields into the added row
            @SuppressWarnings("unchecked")
            Map<String, Object> row = new LinkedHashMap<>((Map<String, Object>) earlier.ops().get(0).value());
            later.ops().forEach(o -> row.put(o.path().substring(1), o.value()));
            return new RowChange(earlier.assignmentRef(), later.deskId(), List.of(new PatchOp("add", "", row)));
        }
        boolean earlierIsRemove = earlier.ops().stream().anyMatch(o -> o.op().equals("remove") && o.path().isEmpty());
        if (earlierIsRemove) return later; // cannot happen for a well-formed stream (a removed row is re-added, not replaced); keep the newer

        Map<String, PatchOp> byPath = new LinkedHashMap<>();
        earlier.ops().forEach(o -> byPath.put(o.path(), o));
        later.ops().forEach(o -> byPath.put(o.path(), o)); // last value per field wins
        return new RowChange(earlier.assignmentRef(), later.deskId(), new ArrayList<>(byPath.values()));
    }
}
