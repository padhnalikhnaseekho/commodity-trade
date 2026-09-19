package io.commodity.logistics.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure unit tests of the diff: no Spring, no database. */
class QagDiffTest {

    private static Map<String, BigDecimal> members(Object... refAndQty) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < refAndQty.length; i += 2) m.put((String) refAndQty[i], new BigDecimal(refAndQty[i + 1].toString()));
        return m;
    }

    // PROVES the worked example in QagDiff's Javadoc.
    @Test
    void reportsAddedRemovedAndModified() {
        var before = members("1.1.1", "100", "1.1.2", "150", "1.1.4", "10");
        var after = members("1.1.1", "100", "1.1.2", "200", "1.1.3", "50");

        QagDiff diff = QagDiff.between(before, after);

        assertThat(diff.added()).containsExactly("1.1.3");
        assertThat(diff.removed()).containsExactly("1.1.4");
        assertThat(diff.modified()).containsExactly("1.1.2");
    }

    @Test
    void identicalMembershipIsAnEmptyDiff() {
        assertThat(QagDiff.between(members("1.1.1", "100"), members("1.1.1", "100")).isEmpty()).isTrue();
    }

    @Test
    void quantitiesCompareByValueNotByScale() {
        assertThat(QagDiff.between(members("1.1.1", "100"), members("1.1.1", "100.0000")).isEmpty()).isTrue();
    }

    // PROVES: refs are ordered numerically, so the published diff is deterministic (1.1.2 before 1.1.10).
    @Test
    void refsAreOrderedNumericallyNotLexicographically() {
        var diff = QagDiff.between(members(), members("1.1.10", "1", "1.1.2", "1", "1.1.1", "1"));
        assertThat(diff.added()).containsExactly("1.1.1", "1.1.2", "1.1.10");
    }

    @Test
    void firstRevisionAddsEverything() {
        var diff = QagDiff.between(Map.of(), members("1.1.1", "5"));
        assertThat(diff.added()).containsExactly("1.1.1");
        assertThat(diff.removed()).isEmpty();
    }
}
