package io.commodity.pricing.service;

import io.commodity.pricing.domain.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Deterministic test data builders. */
final class Fixtures {
    private Fixtures() {}

    /** The benchmark shape: n assignments, each with `params` parameters and `components` price components. */
    static List<AssignmentContent> quota(String quotaRef, int assignments, int params, int components) {
        List<AssignmentContent> out = new ArrayList<>();
        for (int i = 1; i <= assignments; i++) {
            List<ParameterContent> ps = new ArrayList<>();
            for (int p = 0; p < params; p++) ps.add(new ParameterContent("ELEMENT_" + p, BigDecimal.valueOf(p + 1)));
            List<PriceComponentContent> cs = new ArrayList<>();
            for (int c = 0; c < components; c++) {
                cs.add(new PriceComponentContent(ComponentKind.FIXED, BigDecimal.valueOf(10), BigDecimal.valueOf(1000 + c), null, null, null, null, false));
            }
            out.add(new AssignmentContent(quotaRef + "." + i, BigDecimal.valueOf(1000), ps, cs));
        }
        return out;
    }

    /**
     * Returns a copy with the first `count` assignments changed, leaving the rest identical. The change is a quantity bump, so
     * a changed assignment keeps the SAME number of child rows: that keeps the row arithmetic in the tests exact
     * (a changed assignment always costs 1 + parameters + components rows under sharing).
     */
    static List<AssignmentContent> mutateFirst(List<AssignmentContent> content, int count) {
        List<AssignmentContent> out = new ArrayList<>(content);
        for (int i = 0; i < count; i++) out.set(i, out.get(i).withQty(out.get(i).qty().add(BigDecimal.ONE)));
        return out;
    }
}
