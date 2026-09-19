package io.commodity.gateway;

import io.commodity.contracts.lookup.BusinessDayClock;
import io.commodity.contracts.lookup.FunctionalLine;
import io.commodity.contracts.lookup.FunctionalLineDirectory;
import io.commodity.contracts.lookup.QuotaDirectory;
import io.commodity.contracts.lookup.QuotaView;
import io.commodity.contracts.valuation.SubjectLevel;
import io.commodity.contracts.valuation.ValuationEngine;
import io.commodity.contracts.valuation.ValuationInputs;
import io.commodity.contracts.valuation.ValuationInputs.AssignmentInputs;
import io.commodity.contracts.valuation.ValuationInputs.ComponentInput;
import io.commodity.contracts.valuation.ValuationInputs.ParameterInput;
import io.commodity.contracts.valuation.ValuationInputsProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * In-memory stand-ins for everything the gateway reads from other services (trade, pricing, reference data, business day control), behind the
 * same ports the real adapters implement. Static state so several Spring contexts in one test (the restart test) see the same "world".
 */
public final class Fakes {

    static final Map<String, QuotaView> QUOTAS = new ConcurrentHashMap<>();
    static final Map<String, ValuationInputs> SUBJECTS = new ConcurrentHashMap<>();
    static final AtomicReference<LocalDate> BRD = new AtomicReference<>(LocalDate.of(2026, 9, 18));

    private Fakes() {}

    /** Registers a quota (once) and one assignment of it with two components and one parameter; returns the assignment ref. */
    static String assignment(String quotaRef, int seq, String qty, boolean approved, String functionalLineOverride) {
        QUOTAS.putIfAbsent(quotaRef, new QuotaView(quotaRef, quotaRef.split("\\.")[0], "DESK-1", "RM", new BigDecimal("1000000"),
                LocalDate.of(2026, 9, 1), functionalLineOverride));
        String ref = quotaRef + "." + seq;
        var a = new AssignmentInputs(ref, UUID.randomUUID(), new BigDecimal(qty), approved,
                List.of(component(), component()), List.of(new ParameterInput(UUID.randomUUID(), "AG", BigDecimal.ONE)));
        SUBJECTS.put(ref + "|" + SubjectLevel.ASSIGNMENT, new ValuationInputs(ref, SubjectLevel.ASSIGNMENT, BRD.get(), UUID.randomUUID(), UUID.randomUUID(), "RM", List.of(a)));
        return ref;
    }

    /** Flips the approval of an already registered assignment while keeping every id, exactly like approving in pricing does (approval is not a revision). */
    static void setApproved(String ref, boolean approved) {
        SUBJECTS.computeIfPresent(ref + "|" + SubjectLevel.ASSIGNMENT, (k, in) -> {
            var a = in.assignments().get(0);
            return new ValuationInputs(in.subjectRef(), in.level(), in.brd(), in.pqrId(), in.qagrId(), in.businessLine(),
                    List.of(new AssignmentInputs(a.assignmentRef(), a.parId(), a.qty(), approved, a.components(), a.parameters())));
        });
    }

    private static ComponentInput component() {
        return new ComponentInput(UUID.randomUUID(), "FIXED", new BigDecimal("10"), new BigDecimal("5"), null, null, null, null, false);
    }

    @Configuration
    static class Ports {
        @Bean QuotaDirectory quotaDirectory() { return ref -> Optional.ofNullable(QUOTAS.get(ref)); }

        @Bean BusinessDayClock clock() { return desk -> BRD.get(); }

        /** Returns the registered inputs for whatever BRD is asked (the ids are what matters, not the date). */
        @Bean ValuationInputsProvider inputs() {
            return (ref, level, brd) -> Optional.ofNullable(SUBJECTS.get(ref + "|" + level)).map(i ->
                    new ValuationInputs(i.subjectRef(), i.level(), brd, i.pqrId(), i.qagrId(), i.businessLine(), i.assignments()));
        }

        @Bean FunctionalLineDirectory lines() {
            return new FunctionalLineDirectory() {
                public FunctionalLine resolve(String bl, LocalDate created, String override) {
                    String name = override != null ? override : bl + "_MODERN";
                    return new FunctionalLine(name, name.endsWith("_LEGACY") ? ValuationEngine.LEGACY : ValuationEngine.MODERN);
                }
                public boolean isValid(String bl, String name) { return true; }
            };
        }
    }
}
