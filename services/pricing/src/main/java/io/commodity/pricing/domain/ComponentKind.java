package io.commodity.pricing.domain;

/** The three kinds of price, in increasing complexity: a direct value, an index average over a period, or a formula. */
public enum ComponentKind { FIXED, AVERAGE, FORMULA }
