package io.commodity.trade.domain;

import io.commodity.platform.error.DomainException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a total quantity across n parts: evenly at scale 4, remainder to the LAST part.
 *
 * <p>Worked example: 1000.0000 over 3 parts is 333.3333, 333.3333, 333.3334. The shares always add up to the total
 * exactly, which is what makes the quota-quantity invariant (sum(quota.qty) = trade.total_qty) true by construction
 * rather than by luck.
 *
 * <p>INVARIANT: sum of parts equals the total (QuotaPlannerTest, including a sweep over many totals and counts).
 * Plain Java on purpose: this is the code an interviewer might actually read.
 */
public final class QuantitySplitter {

    /** Quantities carry four decimal places (project style rule). */
    public static final int QTY_SCALE = 4;

    private QuantitySplitter() {}

    public static List<BigDecimal> evenSplit(BigDecimal total, int parts) {
        if (parts < 1) throw new IllegalArgumentException("parts must be >= 1");
        BigDecimal base = total.divide(BigDecimal.valueOf(parts), QTY_SCALE, RoundingMode.DOWN);
        if (base.signum() <= 0) {
            throw new DomainException(400, "quantity-too-small", "Quantity is too small to split across the delivery periods")
                    .with("totalQty", total.toPlainString()).with("periods", parts);
        }
        BigDecimal last = total.subtract(base.multiply(BigDecimal.valueOf(parts - 1L)));
        List<BigDecimal> result = new ArrayList<>(parts);
        for (int i = 0; i < parts - 1; i++) result.add(base);
        result.add(last);
        return result;
    }
}
