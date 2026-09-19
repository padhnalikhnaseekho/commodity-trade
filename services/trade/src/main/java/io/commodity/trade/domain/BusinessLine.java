package io.commodity.trade.domain;

/**
 * The four business lines. Drives several behavioural differences downstream (deal granularity,
 * valuation level, lots) but those live in later phases; here it is just a classification.
 * Stored as text (enum name) so the DB stays readable and migrations do not depend on ordinals.
 */
public enum BusinessLine { RM, CONCENTRATES, BULK, ENERGY }
