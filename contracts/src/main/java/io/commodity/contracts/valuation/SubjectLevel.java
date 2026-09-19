package io.commodity.contracts.valuation;

/**
 * What a valuation is struck against. RM values at quota level; the other business lines value at assignment level by default.
 * Lot level exists in the business (an override for concentrates and bulk) but is not built here.
 * The level is explicit in every request and event, because every consumer must handle every level.
 */
public enum SubjectLevel { QUOTA, ASSIGNMENT }
