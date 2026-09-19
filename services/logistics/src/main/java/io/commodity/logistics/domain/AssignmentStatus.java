package io.commodity.logistics.domain;

/**
 * Lifecycle of an assignment (values supplied by the business, not derived from the spec).
 *
 * <p>SUPERSEDED is set on the parent after a split (children replace it); CANCELLED and DELETED are
 * terminal states. Whether SUPERSEDED/CANCELLED/DELETED assignments still count toward the quota's quantity
 * cap is NOT yet specified: confirm before enforcing the quota-quantity invariant in P0.2.
 */
public enum AssignmentStatus { ACTIVE, SUPERSEDED, CANCELLED, DELETED }
