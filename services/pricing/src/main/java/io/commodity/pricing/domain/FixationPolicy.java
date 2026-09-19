package io.commodity.pricing.domain;

import io.commodity.platform.error.DomainException;
import java.math.BigDecimal;

/**
 * INVARIANT: sum(price component quantity) &lt;= assignment quantity. Fixing more than the assignment holds is refused
 * with 409 and the numbers that caused it (FixationPolicyTest, and through the API in PricingApiTest).
 *
 * <p>Enforced under a per-quota lock in the service, so two simultaneous fixations cannot both pass the check and
 * together over-fix the assignment.
 */
public final class FixationPolicy {

    private FixationPolicy() {}

    public static void checkCanFix(AssignmentContent assignment, BigDecimal requested) {
        BigDecimal requestedQty = Decimals.qty(requested); // canonical scale, so the error body is consistent whoever calls
        BigDecimal existing = assignment.pricedQty();
        if (existing.add(requestedQty).compareTo(assignment.qty()) > 0) {
            throw new DomainException(409, "over-fixation", "Price component quantity exceeds assignment quantity")
                    .with("assignmentRef", assignment.assignmentRef())
                    .with("assignmentQty", assignment.qty().toPlainString())
                    .with("existingComponentQty", existing.toPlainString())
                    .with("requestedQty", requestedQty.toPlainString());
        }
    }
}
