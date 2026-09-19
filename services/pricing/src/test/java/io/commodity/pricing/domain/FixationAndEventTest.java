package io.commodity.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.commodity.contracts.events.ChangeKind;
import io.commodity.contracts.events.QagRevisionEvent;
import io.commodity.platform.error.DomainException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FixationAndEventTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }
    private static PriceComponentContent fixed(String qty) {
        return new PriceComponentContent(ComponentKind.FIXED, d(qty), d("5"), null, null, null, null, false);
    }

    // PROVES: fixing may exactly fill the assignment, one more unit is refused, and the refusal carries every number.
    @Test
    void refusesOverFixationWithTheNumbers() {
        var a = AssignmentContent.empty("1.1.3", d("250")).withComponent(fixed("200"));

        assertThatCode(() -> FixationPolicy.checkCanFix(a, d("50"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> FixationPolicy.checkCanFix(a, d("75"))).isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.type()).isEqualTo("over-fixation");
            assertThat(e.details()).containsEntry("assignmentQty", "250.0000").containsEntry("existingComponentQty", "200.0000")
                    .containsEntry("requestedQty", "75.0000");
        });
    }

    private static QagRevisionEvent event(QagRevisionEvent.Member... members) {
        return new QagRevisionEvent(UUID.randomUUID(), null, "1.1", LocalDate.of(2026, 9, 18), List.of(ChangeKind.ALLOCATION),
                List.of(), List.of(), List.of(), List.of(members));
    }

    private static QagRevisionEvent.Member m(String ref, String qty) { return new QagRevisionEvent.Member(ref, d(qty)); }

    // PROVES the worked example in EventApplier's Javadoc.
    @Test
    void appliesAddedRemovedAndModifiedMembersKeepingChildrenOfSurvivors() {
        var a1 = AssignmentContent.empty("1.1.1", d("100")).withComponent(fixed("10")).withComponent(fixed("20"));
        var a2 = AssignmentContent.empty("1.1.2", d("50"));

        var result = EventApplier.apply(List.of(a1, a2), event(m("1.1.1", "120"), m("1.1.3", "50")));

        assertThat(result).extracting(AssignmentContent::assignmentRef).containsExactly("1.1.1", "1.1.3");
        assertThat(result.get(0).qty()).isEqualByComparingTo("120");
        assertThat(result.get(0).components()).isEqualTo(a1.components()); // children kept
        assertThat(result.get(1)).isEqualTo(AssignmentContent.empty("1.1.3", d("50"))); // new = nothing priced
    }

    @Test
    void anUnchangedMemberResolvesToEqualContent() {
        var a1 = AssignmentContent.empty("1.1.1", d("100")).withComponent(fixed("10"));
        assertThat(EventApplier.apply(List.of(a1), event(m("1.1.1", "100.0000")))).containsExactly(a1);
    }

    // PROVES the race fallback: a quantity below the fixed quantity is applied and reported over-fixed, not rejected.
    @Test
    void aQuantityBelowTheFixedQuantityIsAppliedAndFlaggedOverFixed() {
        var a1 = AssignmentContent.empty("1.1.1", d("100")).withComponent(fixed("80"));
        var result = EventApplier.apply(List.of(a1), event(m("1.1.1", "60"))).get(0);
        assertThat(result.overFixed()).isTrue();
        assertThat(result.unpricedQty()).isEqualByComparingTo("-20");
    }
}
