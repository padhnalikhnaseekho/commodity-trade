package io.commodity.stubs.engine;

import java.math.BigDecimal;
import java.util.Map;

/** Tunables of the stub engine: base price, per-lane delay, and the failure knob for showing retry and DLQ behaviour on demand. */
public record EngineSettings(BigDecimal basePrice, Map<String, Long> delaysMs, double failureRate, int failAttempts) {}
