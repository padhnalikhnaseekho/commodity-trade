package io.commodity.gateway.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.commodity.contracts.valuation.SubjectLevel;
import io.commodity.contracts.valuation.ValuationInputs;
import io.commodity.contracts.valuation.ValuationInputs.AssignmentInputs;
import io.commodity.contracts.valuation.ValuationInputs.ComponentInput;
import io.commodity.contracts.valuation.ValuationInputs.ParameterInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit tests of the idempotency key: no Spring, no database. */
class RequestKeyTest {

    private static final LocalDate BRD = LocalDate.of(2026, 9, 18);
    private static final UUID PQR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID PAR = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID PC1 = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID PC2 = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID PP1 = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID PP2 = UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    private static ComponentInput component(UUID id) {
        return new ComponentInput(id, "FIXED", new BigDecimal("10"), new BigDecimal("5"), null, null, null, null, false);
    }

    private static ValuationInputs inputs(String ref, LocalDate brd, UUID pqr, UUID par, List<ComponentInput> cs, List<ParameterInput> ps) {
        return new ValuationInputs(ref, SubjectLevel.ASSIGNMENT, brd, pqr, null, "RM",
                List.of(new AssignmentInputs(ref, par, new BigDecimal("100"), true, cs, ps)));
    }

    private static ValuationInputs base() {
        return inputs("1.1.3", BRD, PQR, PAR, List.of(component(PC1), component(PC2)),
                List.of(new ParameterInput(PP1, "AG", BigDecimal.ONE), new ParameterInput(PP2, "AS", BigDecimal.TEN)));
    }

    // PROVES acceptance: same inputs produce the same request key (and it has the documented "sha256:" form).
    @Test
    void sameInputsProduceTheSameKey() {
        String a = RequestKey.of(base(), "RM_MODERN", BRD);
        String b = RequestKey.of(base(), "RM_MODERN", BRD);
        assertThat(a).isEqualTo(b).startsWith("sha256:").hasSize("sha256:".length() + 64);
    }

    @Test
    void theOrderOfComponentsAndParametersCannotChangeTheKey() {
        var shuffled = inputs("1.1.3", BRD, PQR, PAR, List.of(component(PC2), component(PC1)),
                List.of(new ParameterInput(PP2, "AS", BigDecimal.TEN), new ParameterInput(PP1, "AG", BigDecimal.ONE)));
        assertThat(RequestKey.of(shuffled, "RM_MODERN", BRD)).isEqualTo(RequestKey.of(base(), "RM_MODERN", BRD));
    }

    // PROVES the key changes with every input it is documented to depend on (a stale answer can never be served for changed inputs).
    @Test
    void everyDocumentedInputChangesTheKey() {
        String base = RequestKey.of(base(), "RM_MODERN", BRD);
        UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

        assertThat(RequestKey.of(inputs("1.1.4", BRD, PQR, PAR, base().assignments().get(0).components(), base().assignments().get(0).parameters()), "RM_MODERN", BRD)).isNotEqualTo(base);
        assertThat(RequestKey.of(base(), "RM_LEGACY", BRD)).isNotEqualTo(base);                                       // functional line
        assertThat(RequestKey.of(base(), "RM_MODERN", BRD.plusDays(1))).isNotEqualTo(base);                           // market data date
        assertThat(RequestKey.of(inputs("1.1.3", BRD.plusDays(1), PQR, PAR, base().assignments().get(0).components(), base().assignments().get(0).parameters()), "RM_MODERN", BRD)).isNotEqualTo(base); // brd
        assertThat(RequestKey.of(inputs("1.1.3", BRD, other, PAR, base().assignments().get(0).components(), base().assignments().get(0).parameters()), "RM_MODERN", BRD)).isNotEqualTo(base);  // pqrId
        assertThat(RequestKey.of(inputs("1.1.3", BRD, PQR, other, base().assignments().get(0).components(), base().assignments().get(0).parameters()), "RM_MODERN", BRD)).isNotEqualTo(base);  // parId
        assertThat(RequestKey.of(inputs("1.1.3", BRD, PQR, PAR, List.of(component(PC1)), base().assignments().get(0).parameters()), "RM_MODERN", BRD)).isNotEqualTo(base);              // a component removed
        assertThat(RequestKey.of(inputs("1.1.3", BRD, PQR, PAR, base().assignments().get(0).components(), List.of(new ParameterInput(PP1, "AG", BigDecimal.ONE))), "RM_MODERN", BRD)).isNotEqualTo(base); // a parameter removed
    }

    // PROVES field boundaries are unambiguous: moving an id between the component list and the parameter list must change the key.
    @Test
    void fieldBoundariesAreUnambiguous() {
        var asComponent = inputs("1.1.3", BRD, PQR, PAR, List.of(component(PC1)), List.of());
        var asParameter = inputs("1.1.3", BRD, PQR, PAR, List.of(), List.of(new ParameterInput(PC1, "AG", BigDecimal.ONE)));
        assertThat(RequestKey.of(asComponent, "RM_MODERN", BRD)).isNotEqualTo(RequestKey.of(asParameter, "RM_MODERN", BRD));
    }
}
