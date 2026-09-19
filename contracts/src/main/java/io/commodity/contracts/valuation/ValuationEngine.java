package io.commodity.contracts.valuation;

/**
 * The engine a request is routed to. The switch between them is REFERENCE DATA (the FunctionalLine), not code, so retiring an
 * engine is a configuration change and a migration, not a release.
 */
public enum ValuationEngine { LEGACY, MODERN }
