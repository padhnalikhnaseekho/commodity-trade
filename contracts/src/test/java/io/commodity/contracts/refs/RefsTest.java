package io.commodity.contracts.refs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure unit tests: no Spring, no database. */
class RefsTest {

    // PROVES: exit criterion "trade 1 -> quotas 1.1, 1.2 -> assignments 1.1.1, 1.1.2".
    @Test
    void refsNestAsTradeQuotaAssignment() {
        TradeRef trade = new TradeRef(1);
        QuotaRef q1 = trade.quota(1);
        QuotaRef q2 = trade.quota(2);

        assertThat(trade).hasToString("1");
        assertThat(q1).hasToString("1.1");
        assertThat(q2).hasToString("1.2");
        assertThat(q1.assignment(1)).hasToString("1.1.1");
        assertThat(q1.assignment(2)).hasToString("1.1.2");
    }

    @Test
    void parseIsTheInverseOfToString() {
        assertThat(TradeRef.parse("7")).isEqualTo(new TradeRef(7));
        assertThat(QuotaRef.parse("7.3")).isEqualTo(new TradeRef(7).quota(3));
        assertThat(AssignmentRef.parse("7.3.9")).isEqualTo(new TradeRef(7).quota(3).assignment(9));
    }

    @Test
    void parseRejectsWrongShapeOrNonPositiveParts() {
        assertThatThrownBy(() -> QuotaRef.parse("1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuotaRef.parse("1.2.3")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AssignmentRef.parse("1.a.3")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TradeRef(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaRef(new TradeRef(1), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // PROVES: text refs must be sorted numerically. Lexicographic order would put 1.10 before 1.2.
    @Test
    void naturalOrderingIsNumericNotLexicographic() {
        TradeRef t = new TradeRef(1);
        List<QuotaRef> sorted = List.of(t.quota(10), t.quota(2), t.quota(1)).stream().sorted().toList();
        assertThat(sorted).extracting(QuotaRef::toString).containsExactly("1.1", "1.2", "1.10");
        assertThat("1.10".compareTo("1.2")).isNegative(); // the trap the comparator avoids
    }
}
