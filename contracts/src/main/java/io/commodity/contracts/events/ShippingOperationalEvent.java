package io.commodity.contracts.events;

import java.time.LocalDate;

/**
 * Published on {@code logistics.shipping.operational.v1} (key: assignmentRef) for OPERATIONAL changes (load,
 * discharge, bill of lading, insurance transfer). This is the high-volume firehose: it is deliberately a separate
 * topic from the revision stream so that no consumer of QAG revisions pays for it. No consumer in P0.
 */
public record ShippingOperationalEvent(String assignmentRef, String quotaRef, ChangeKind changeKind, LocalDate brd) {}
