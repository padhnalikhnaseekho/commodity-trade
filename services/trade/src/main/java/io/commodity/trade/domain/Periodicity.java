package io.commodity.trade.domain;

/**
 * Delivery schedule granularity a quota was derived from. Only MONTHLY has a specified
 * derivation rule so far (even split, remainder to last); WEEKLY/DAILY/CUSTOM rules must be
 * confirmed before P0.2 rather than invented (project conventions rule 1).
 */
public enum Periodicity { MONTHLY, WEEKLY, DAILY, CUSTOM }
