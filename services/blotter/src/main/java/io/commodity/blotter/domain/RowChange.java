package io.commodity.blotter.domain;

import java.util.List;

/**
 * A change to ONE blotter row, expressed as JSON Patch operations (RFC 6902) relative to the row: {@code replace} of individual fields, {@code add} at the
 * root path "" for a row that is new to the view, {@code remove} at the root for one that left it.
 *
 * <p>WHY deltas and not rows: a blotter row has many columns and changes often; pushing only the changed fields keeps the stream small and lets the client
 * apply it with no lookups. {@code deskId} travels with the change so the stream can be filtered per subscriber without reading the row.
 */
public record RowChange(String assignmentRef, String deskId, List<PatchOp> ops) {

    /** One JSON Patch operation. {@code path} is "" (the whole row) or "/field". {@code value} is absent for remove. */
    public record PatchOp(String op, String path, Object value) {}

    /** Convenience for the server; not part of the wire format (a client has no use for it). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() {
        return ops.isEmpty();
    }
}
