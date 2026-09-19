package io.commodity.contracts.lookup;

/**
 * Port for asking pricing whether an assignment has any price fixation (any price component).
 *
 * <p>Business rule (owner decision): while an assignment has a fixation, its quantity cannot be edited. Quantity is owned
 * by logistics and fixations by pricing, and a service may not read another's tables, so logistics asks through this
 * port (HTTP adapter in production, fake in tests) before it accepts a quantity change.
 *
 * <p>TRADEOFF: a synchronous call from logistics to pricing couples their availability on this one path. The check is
 * inherently racy too (a fixation can land between the check and the commit). Pricing's consumer therefore also
 * tolerates the race by flagging the assignment as over-fixed instead of rejecting the event.
 */
public interface FixationDirectory {
    boolean hasFixation(String assignmentRef);
}
