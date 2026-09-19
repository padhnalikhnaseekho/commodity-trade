package io.commodity.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.commodity.platform.error.DomainException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure unit tests of hashing and content canonicalisation: no Spring, no database. */
class ContentHasherTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }
    private static ParameterContent param(String element, String value) { return new ParameterContent(element, d(value)); }
    private static PriceComponentContent fixed(String qty, String price) {
        return new PriceComponentContent(ComponentKind.FIXED, d(qty), d(price), null, null, null, null, false);
    }
    private static AssignmentContent assignment(String ref, String qty, List<ParameterContent> ps, List<PriceComponentContent> cs) {
        return new AssignmentContent(ref, d(qty), ps, cs);
    }

    // PROVES the interview claim "10.0 and 10.00 hash alike": scale must not create a false difference.
    @Test
    void decimalsWithDifferentScaleHashAlike() {
        assertThat(ContentHasher.hash(param("AG", "10.0"))).isEqualTo(ContentHasher.hash(param("AG", "10.00")));
        assertThat(ContentHasher.hash(fixed("10", "5.5"))).isEqualTo(ContentHasher.hash(fixed("10.0000", "5.500000")));
    }

    // PROVES: list order can never create a false difference (children are sorted before hashing).
    @Test
    void childOrderDoesNotChangeTheAssignmentHash() {
        var a = assignment("1.1.1", "100", List.of(param("AG", "1"), param("AS", "2")), List.of(fixed("10", "5"), fixed("20", "6")));
        var b = assignment("1.1.1", "100", List.of(param("AS", "2"), param("AG", "1")), List.of(fixed("20", "6"), fixed("10", "5")));
        assertThat(ContentHasher.hash(a)).isEqualTo(ContentHasher.hash(b));
        assertThat(a).isEqualTo(b); // canonical by construction, so plain equality works for the equivalence property
    }

    // PROVES the bottom-up property: a change anywhere in the subtree changes the root hash.
    @Test
    void aChangeAnywhereInTheSubtreeChangesTheRootHash() {
        var base = assignment("1.1.1", "100", List.of(param("AG", "1")), List.of(fixed("10", "5")));
        String h = ContentHasher.hash(base);

        assertThat(ContentHasher.hash(base.withQty(d("101")))).isNotEqualTo(h);
        assertThat(ContentHasher.hash(base.withParameter(param("AS", "2")))).isNotEqualTo(h);
        assertThat(ContentHasher.hash(assignment("1.1.1", "100", List.of(param("AG", "1.000001")), List.of(fixed("10", "5"))))).isNotEqualTo(h);
        assertThat(ContentHasher.hash(assignment("1.1.1", "100", List.of(param("AG", "1")), List.of(fixed("10", "5.000001"))))).isNotEqualTo(h);
        assertThat(ContentHasher.hash(assignment("1.1.2", "100", List.of(param("AG", "1")), List.of(fixed("10", "5"))))).isNotEqualTo(h);
    }

    @Test
    void identicalContentHashesIdentically() {
        var a = assignment("1.1.1", "100", List.of(param("AG", "1")), List.of(fixed("10", "5")));
        var b = assignment("1.1.1", "100", List.of(param("AG", "1")), List.of(fixed("10", "5")));
        assertThat(ContentHasher.hash(a)).isEqualTo(ContentHasher.hash(b)).hasSize(64);
    }

    // PROVES: field boundaries are unambiguous (length-prefixed) and null differs from empty.
    @Test
    void fieldBoundariesAndNullsAreUnambiguous() {
        var avg = (java.util.function.BiFunction<String, String, PriceComponentContent>) (index, formula) ->
                new PriceComponentContent(ComponentKind.FORMULA, d("1"), null, index, null, null, formula == null ? "x" : formula, false);
        assertThat(ContentHasher.hash(avg.apply("ab", "c"))).isNotEqualTo(ContentHasher.hash(avg.apply("a", "bc")));
        assertThat(ContentHasher.hash(avg.apply(null, "f"))).isNotEqualTo(ContentHasher.hash(avg.apply("", "f")));
    }

    // PROVES the DERIVED values: unpriced quantity is computed, never stored, and can go negative (over-fixed).
    @Test
    void pricedUnpricedAndOverFixedAreDerived() {
        var a = assignment("1.1.1", "100", List.of(), List.of(fixed("10", "5"), fixed("70", "6")));
        assertThat(a.pricedQty()).isEqualByComparingTo("80");
        assertThat(a.unpricedQty()).isEqualByComparingTo("20");
        assertThat(a.overFixed()).isFalse();
        assertThat(a.hasFixation()).isTrue();

        var reduced = a.withQty(d("60"));
        assertThat(reduced.unpricedQty()).isEqualByComparingTo("-20");
        assertThat(reduced.overFixed()).isTrue();
        assertThat(AssignmentContent.empty("1.1.1", d("5")).hasFixation()).isFalse();
    }

    @Test
    void componentsAreValidatedByKindAndPrecisionIsNeverSilentlyRounded() {
        assertThatThrownBy(() -> new PriceComponentContent(ComponentKind.FIXED, d("1"), null, null, null, null, null, false))
                .isInstanceOf(DomainException.class).hasMessageContaining("fixedPrice");
        assertThatThrownBy(() -> new PriceComponentContent(ComponentKind.AVERAGE, d("1"), null, "LME", null, null, null, false))
                .isInstanceOf(DomainException.class).hasMessageContaining("periodFrom");
        assertThatThrownBy(() -> new PriceComponentContent(ComponentKind.FORMULA, d("1"), null, null, null, null, " ", false))
                .isInstanceOf(DomainException.class).hasMessageContaining("formula");
        assertThatThrownBy(() -> new PriceComponentContent(ComponentKind.AVERAGE, d("1"), null, "LME",
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), null, false)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> fixed("0", "5")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> fixed("1.00001", "5")).isInstanceOf(DomainException.class).hasMessageContaining("decimal places");
    }
}
