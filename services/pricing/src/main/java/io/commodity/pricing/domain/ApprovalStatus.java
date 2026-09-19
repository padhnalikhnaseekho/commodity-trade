package io.commodity.pricing.domain;

/**
 * Approval state of an assignment, owned by pricing. Approval must be complete before the desk can close (a check for Close of Books, a later
 * phase); it does NOT stop a valuation: valuing an unapproved assignment simply produces a PROVISIONAL number.
 * Kept as its own insert-only record (see V2 migration), so approving never cuts a pricing revision and no default is
 * stored: an assignment with no approval record is UNAPPROVED.
 */
public enum ApprovalStatus { APPROVED, UNAPPROVED }
