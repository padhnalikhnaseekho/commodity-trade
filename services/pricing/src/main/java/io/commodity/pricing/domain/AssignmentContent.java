package io.commodity.pricing.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Everything pricing knows about one assignment at one moment: quantity, parameters and price components. It is the
 * unit that structural sharing copies or shares, and the value a revision resolves to.
 *
 * <p>Canonical by construction: decimals are fixed-scale and both child lists are sorted by content hash, so two
 * assignments with the same content are {@code equals}, whatever order things arrived in. That is what lets the
 * equivalence property compare the two write strategies with a plain equality check.
 *
 * <p>DERIVED values (never stored, project rule 3): {@link #pricedQty()}, {@link #unpricedQty()}, {@link #overFixed()}.
 * Unpriced quantity is the market exposure; storing it would mean rewriting it on every change, which is exactly the
 * write amplification this design exists to remove.
 */
public record AssignmentContent(String assignmentRef, BigDecimal qty, List<ParameterContent> parameters,
                                List<PriceComponentContent> components) {

    private static final Comparator<ParameterContent> PARAMETER_ORDER = Comparator.comparing(ContentHasher::hash);
    private static final Comparator<PriceComponentContent> COMPONENT_ORDER = Comparator.comparing(ContentHasher::hash);

    public AssignmentContent {
        if (assignmentRef == null || assignmentRef.isBlank()) throw new IllegalArgumentException("assignmentRef required");
        qty = Decimals.qty(java.util.Objects.requireNonNull(qty, "qty required"));
        parameters = parameters == null ? List.of() : parameters.stream().sorted(PARAMETER_ORDER).toList();
        components = components == null ? List.of() : components.stream().sorted(COMPONENT_ORDER).toList();
    }

    /** A brand-new assignment with nothing priced yet. */
    public static AssignmentContent empty(String assignmentRef, BigDecimal qty) {
        return new AssignmentContent(assignmentRef, qty, List.of(), List.of());
    }

    public AssignmentContent withQty(BigDecimal newQty) {
        return new AssignmentContent(assignmentRef, newQty, parameters, components);
    }

    public AssignmentContent withParameter(ParameterContent p) {
        return new AssignmentContent(assignmentRef, qty, java.util.stream.Stream.concat(parameters.stream(), java.util.stream.Stream.of(p)).toList(), components);
    }

    public AssignmentContent withComponent(PriceComponentContent c) {
        return new AssignmentContent(assignmentRef, qty, parameters, java.util.stream.Stream.concat(components.stream(), java.util.stream.Stream.of(c)).toList());
    }

    /** Sum of the quantities of all price components (derived). Starts from a scale-4 zero so the result is always canonical. */
    public BigDecimal pricedQty() {
        return components.stream().map(PriceComponentContent::qty).reduce(Decimals.qty(BigDecimal.ZERO), BigDecimal::add);
    }

    /** Assignment quantity minus priced quantity (derived). Negative means the assignment is over-fixed. */
    public BigDecimal unpricedQty() {
        return qty.subtract(pricedQty());
    }

    /** True when fixed quantity exceeds the assignment quantity (only possible through the event race described in the addendum). */
    public boolean overFixed() {
        return unpricedQty().signum() < 0;
    }

    /** True when any price component exists: the condition under which logistics may not edit the quantity. */
    public boolean hasFixation() {
        return !components.isEmpty();
    }
}
