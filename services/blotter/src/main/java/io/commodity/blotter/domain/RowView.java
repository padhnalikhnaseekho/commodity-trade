package io.commodity.blotter.domain;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row of the blotter as the UI sees it: an assignment with its priced and unpriced quantity, approval, and (if any) its latest valuation.
 *
 * <p>Why it exists: the blotter is a MATERIALISED READ MODEL, a flat denormalised row assembled from several services (trade, pricing, valuation) so the UI
 * never joins across services. It is disposable: it is rebuilt from the event streams, and nothing else reads it as a source of truth.
 *
 * <p>The valuation columns follow the spec (valuation, valuationAsOf) plus the level, engine and a PROVISIONAL flag. For an RM quota the valuation is
 * struck at QUOTA level, so every assignment row of that quota shows the same quota number, marked level QUOTA (no pro-rata split: none was specified).
 * {@code provisional} is DERIVED at read time from the current approval state (assignment level: this assignment is not approved; quota level: any
 * assignment of the quota is not approved), so it can never be stale.
 *
 * <p>NOTE on derived values: priced and unpriced quantity are stored HERE because this table is a projection, not the system of record. The rule "derived
 * values are never stored" protects pricing's revision tables from write amplification; a disposable read model rebuilt from events is the standard exception.
 */
public record RowView(String assignmentRef, String tradeRef, String quotaRef, String deskId, String businessLine, String commodity, LocalDate brd,
                      String qty, String pricedQty, String unpricedQty, boolean overFixed, String approvalStatus, String status,
                      String valuation, LocalDate valuationAsOf, String valuationLevel, String valuationEngine, Boolean provisional) {

    public RowView withProvisional(Boolean value) {
        return new RowView(assignmentRef, tradeRef, quotaRef, deskId, businessLine, commodity, brd, qty, pricedQty, unpricedQty, overFixed, approvalStatus,
                status, valuation, valuationAsOf, valuationLevel, valuationEngine, value);
    }

    /** The row as an ordered map of field name to value: the shape patches are computed over (and what a whole-row "add" carries). */
    public Map<String, Object> fields() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assignmentRef", assignmentRef);
        m.put("tradeRef", tradeRef);
        m.put("quotaRef", quotaRef);
        m.put("deskId", deskId);
        m.put("businessLine", businessLine);
        m.put("commodity", commodity);
        m.put("brd", brd == null ? null : brd.toString());
        m.put("qty", qty);
        m.put("pricedQty", pricedQty);
        m.put("unpricedQty", unpricedQty);
        m.put("overFixed", overFixed);
        m.put("approvalStatus", approvalStatus);
        m.put("status", status);
        m.put("valuation", valuation);
        m.put("valuationAsOf", valuationAsOf == null ? null : valuationAsOf.toString());
        m.put("valuationLevel", valuationLevel);
        m.put("valuationEngine", valuationEngine);
        m.put("provisional", provisional);
        return m;
    }
}
