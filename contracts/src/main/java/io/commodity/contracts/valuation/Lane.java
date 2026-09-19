package io.commodity.contracts.valuation;

/**
 * Priority lane of a valuation request. Interactive and bulk traffic are opposite workloads on the same engines: interactive is
 * low volume and latency-critical (a trader waiting on a what-if), bulk is high volume and throughput-bound (a market-data
 * revaluation sweep). Each lane gets its own bounded in-flight budget in the gateway, so a bulk sweep can never exhaust the
 * interactive budget. The caller chooses the lane; the gateway enforces the isolation.
 */
public enum Lane { INTERACTIVE, INVOICE, BULK, CLOSE }
