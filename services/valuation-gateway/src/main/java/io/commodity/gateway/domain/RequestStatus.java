package io.commodity.gateway.domain;

/**
 * Lifecycle of a valuation request. PENDING (accepted, waiting for a lane slot) -> SENT (handed to the outbox for the engine) ->
 * COMPLETED or FAILED. A timeout or an error reply sends a SENT request back to PENDING for another attempt, until the attempt
 * budget is spent, then FAILED. COMPLETED and FAILED are terminal.
 */
public enum RequestStatus { PENDING, SENT, COMPLETED, FAILED }
