package io.commodity.blotter.domain;

import io.commodity.contracts.refs.AssignmentRef;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives the PROVISIONAL flag of each row's valuation from the CURRENT approval state (owner rule: approval must be complete before desk close, but a
 * valuation of an unapproved assignment is still produced and is provisional).
 *
 * <ul>
 *   <li>No valuation on the row: no flag (null).</li>
 *   <li>Assignment-level valuation: provisional while THIS assignment is not APPROVED.</li>
 *   <li>Quota-level valuation (RM): provisional while ANY assignment of the quota is not APPROVED, because all of them contribute to the one number.</li>
 * </ul>
 * Derived on read, never stored, so approving an assignment flips the flag with no valuation recomputed (approval is not part of the valuation's key).
 */
public final class Provisional {

    private Provisional() {}

    public static List<RowView> apply(List<RowView> rows) {
        Map<String, Boolean> quotaFullyApproved = new HashMap<>();
        for (RowView r : rows) quotaFullyApproved.merge(r.quotaRef(), "APPROVED".equals(r.approvalStatus()), Boolean::logicalAnd);

        return rows.stream().map(r -> {
            if (r.valuation() == null) return r.withProvisional(null);
            boolean approved = "QUOTA".equals(r.valuationLevel()) ? quotaFullyApproved.get(r.quotaRef()) : "APPROVED".equals(r.approvalStatus());
            return r.withProvisional(!approved);
        }).sorted(Comparator.comparing(r -> AssignmentRef.parse(r.assignmentRef()))).toList();
    }
}
