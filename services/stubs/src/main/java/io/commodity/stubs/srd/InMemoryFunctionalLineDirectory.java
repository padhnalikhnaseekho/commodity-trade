package io.commodity.stubs.srd;

import io.commodity.contracts.lookup.FunctionalLine;
import io.commodity.contracts.lookup.FunctionalLineDirectory;
import io.commodity.contracts.valuation.ValuationEngine;
import io.commodity.platform.error.DomainException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * STUB of the static reference data service (SRD), holding only what valuation routing needs.
 *
 * <p>Lines: RM has RM_LEGACY (legacy engine) and RM_MODERN; every other business line has a single "_MODERN" line. Resolution:
 * an explicit override wins (if valid for the business line); otherwise RM trades created BEFORE the cutover BRD use the legacy
 * line and everything else the modern one.
 *
 * <p>WHY this is reference data: retiring the legacy engine becomes "move the cutover date / re-point the line", a data change and a
 * migration, not a release. TARGET: the real SRD publishes compacted topics that consumers materialise locally; the stub is fixed
 * in memory behind the same port, so replacing it changes no caller.
 */
public class InMemoryFunctionalLineDirectory implements FunctionalLineDirectory {

    private static final Map<String, List<String>> LINES = Map.of(
            "RM", List.of("RM_LEGACY", "RM_MODERN"),
            "CONCENTRATES", List.of("CONCENTRATES_MODERN"),
            "BULK", List.of("BULK_MODERN"),
            "ENERGY", List.of("ENERGY_MODERN"));

    private final LocalDate rmCutoverBrd;

    public InMemoryFunctionalLineDirectory(LocalDate rmCutoverBrd) {
        this.rmCutoverBrd = rmCutoverBrd;
    }

    @Override
    public FunctionalLine resolve(String businessLine, LocalDate tradeCreatedBrd, String override) {
        if (override != null) {
            if (!isValid(businessLine, override)) {
                throw new DomainException(400, "invalid-functional-line", "Not a functional line of this business line")
                        .with("businessLine", businessLine).with("functionalLine", override).with("allowed", String.valueOf(LINES.get(businessLine)));
            }
            return line(override);
        }
        boolean legacy = "RM".equals(businessLine) && tradeCreatedBrd != null && tradeCreatedBrd.isBefore(rmCutoverBrd);
        return line(legacy ? "RM_LEGACY" : businessLine + "_MODERN");
    }

    @Override
    public boolean isValid(String businessLine, String name) {
        return LINES.getOrDefault(businessLine, List.of()).contains(name);
    }

    private static FunctionalLine line(String name) {
        return new FunctionalLine(name, name.endsWith("_LEGACY") ? ValuationEngine.LEGACY : ValuationEngine.MODERN);
    }
}
