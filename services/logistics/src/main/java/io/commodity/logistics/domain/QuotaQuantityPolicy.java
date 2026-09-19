package io.commodity.logistics.domain;

import io.commodity.platform.error.DomainException;
import java.math.BigDecimal;

/**
 * INVARIANT: the total quantity of ACTIVE assignments in a quota never exceeds the quota's quantity
 * (QuotaQuantityPolicyTest; also exercised through the API in AssignmentApiTest).
 *
 * <p>Only ACTIVE assignments count: SUPERSEDED (replaced by a split), CANCELLED and DELETED ones no longer hold
 * quantity. The error carries every number involved so the caller can see exactly why it was refused.
 */
public final class QuotaQuantityPolicy {

    private QuotaQuantityPolicy() {}

    /**
     * @param quotaQty      quantity of the quota (owned by trade)
     * @param otherActive   sum of ACTIVE assignments, excluding the assignment being created or changed
     * @param requested     the quantity the assignment would hold after this change
     */
    public static void check(String quotaRef, BigDecimal quotaQty, BigDecimal otherActive, BigDecimal requested) {
        if (otherActive.add(requested).compareTo(quotaQty) > 0) {
            throw new DomainException(409, "quota-quantity-exceeded", "Assignment quantity exceeds the quota quantity")
                    .with("quotaRef", quotaRef)
                    .with("quotaQty", quotaQty.toPlainString())
                    .with("existingAssignedQty", otherActive.toPlainString())
                    .with("requestedQty", requested.toPlainString());
        }
    }
}
