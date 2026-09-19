package io.commodity.contracts.events;

import io.commodity.contracts.valuation.ValuationEngine;
import java.util.UUID;

/**
 * Published on {@code valuation.reply.v1} (key: requestId) by the engine adapter. The gateway correlates it to its request through
 * the DATABASE (by requestId), never through an in-memory map, so any gateway instance can handle any reply and a restart loses nothing.
 * Exactly one of {@code result} / {@code error} is set. The result is a decimal string (no float loss).
 */
public record ValuationCompleted(UUID requestId, String requestKey, ValuationEngine engine, boolean success, String result, String error) {}
