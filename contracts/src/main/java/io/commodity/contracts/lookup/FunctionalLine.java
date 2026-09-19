package io.commodity.contracts.lookup;

import io.commodity.contracts.valuation.ValuationEngine;

/**
 * A FunctionalLine is a child of a business line held in reference data (for example RM legacy and RM modern). It carries the
 * engine that values it, which is how the engine migration is expressed as data rather than code.
 */
public record FunctionalLine(String name, ValuationEngine engine) {}
