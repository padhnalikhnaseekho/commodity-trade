package io.commodity.pricing.domain;

/**
 * Approval state of an assignment, owned by pricing: an assignment must be APPROVED to be eligible for valuation and P&amp;L.
 * Kept as its own insert-only record (see V2 migration), so approving never cuts a pricing revision and no default is
 * stored: an assignment with no approval record is UNAPPROVED.
 */
public enum ApprovalStatus { APPROVED, UNAPPROVED }
