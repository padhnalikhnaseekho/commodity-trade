package io.commodity.pricing.domain;

import java.math.BigDecimal;

/**
 * A measured physical parameter that adjusts price (silver premium, arsenic penalty, coal moisture or particle size).
 * Pure value: identity in the database is a row id, but two parameters with equal content are interchangeable, which is
 * what lets content hashes decide sharing.
 */
public record ParameterContent(String element, BigDecimal value) {
    public ParameterContent {
        if (element == null || element.isBlank()) throw new IllegalArgumentException("parameter element required");
        value = Decimals.price(java.util.Objects.requireNonNull(value, "parameter value required"));
    }
}
