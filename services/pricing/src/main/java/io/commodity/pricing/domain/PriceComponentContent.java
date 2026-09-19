package io.commodity.pricing.domain;

import io.commodity.platform.error.DomainException;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A quantity-scoped price attached to an assignment: FIXED (a direct value), AVERAGE (index over a period) or FORMULA.
 *
 * <p>Quantity-scoped because fixation is incremental: fix 10 MT of a 100 MT assignment and the other 90 MT stays unpriced.
 * The fields that apply depend on the kind (validated here, so no invalid component can be constructed):
 * FIXED needs fixedPrice; AVERAGE needs indexName, periodFrom and periodTo; FORMULA needs formula. Formula syntax is not
 * checked: pricing runs only initial checks and the engine owns semantics.
 */
public record PriceComponentContent(ComponentKind kind, BigDecimal qty, BigDecimal fixedPrice, String indexName,
                                    LocalDate periodFrom, LocalDate periodTo, String formula, boolean provisional) {

    public PriceComponentContent {
        java.util.Objects.requireNonNull(kind, "kind required");
        qty = Decimals.qty(java.util.Objects.requireNonNull(qty, "qty required"));
        if (qty.signum() <= 0) throw invalid("Component quantity must be positive");
        fixedPrice = Decimals.price(fixedPrice);
        switch (kind) {
            case FIXED -> {
                if (fixedPrice == null) throw invalid("A FIXED component needs fixedPrice");
            }
            case AVERAGE -> {
                if (indexName == null || indexName.isBlank() || periodFrom == null || periodTo == null) {
                    throw invalid("An AVERAGE component needs indexName, periodFrom and periodTo");
                }
                if (periodTo.isBefore(periodFrom)) throw invalid("AVERAGE period ends before it starts");
            }
            case FORMULA -> {
                if (formula == null || formula.isBlank()) throw invalid("A FORMULA component needs a formula");
            }
        }
    }

    private static DomainException invalid(String title) {
        return new DomainException(400, "invalid-price-component", title);
    }
}
