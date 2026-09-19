package io.commodity.pricing.api;

import java.time.LocalDate;

/** Request bodies of the pricing API. Quantities and prices are strings (no float loss). */
public final class PricingRequests {
    private PricingRequests() {}

    public record Component(String kind, String qty, String fixedPrice, String indexName, LocalDate periodFrom,
                            LocalDate periodTo, String formula, Boolean provisional) {}

    public record Parameter(String element, String value) {}

    public record Approval(String status) {}

    /** Answer of GET /api/assignments/{ref}/fixation (the shape the platform's HttpFixationDirectory reads). */
    public record Fixation(String assignmentRef, boolean fixed) {}
}
