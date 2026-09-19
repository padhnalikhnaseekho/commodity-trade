package io.commodity.trade.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.commodity.platform.error.DomainException;
import io.commodity.trade.domain.QuotaPlanner.CustomPeriod;
import io.commodity.trade.domain.QuotaPlanner.PlannedQuota;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure unit tests: no Spring, no database. The code under test is plain Java (project rule). */
class QuotaPlannerTest {

    private static BigDecimal qty(String v) { return new BigDecimal(v).setScale(4); }
    private static LocalDate d(String iso) { return LocalDate.parse(iso); }
    private static BigDecimal sum(List<PlannedQuota> quotas) {
        return quotas.stream().map(PlannedQuota::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // PROVES exit criterion: a monthly delivery schedule over three months produces three quotas.
    @Test
    void monthlyScheduleOverThreeMonthsProducesThreeQuotas() {
        var quotas = QuotaPlanner.plan(Periodicity.MONTHLY, d("2026-01-01"), d("2026-03-31"), qty("900"), null);

        assertThat(quotas).hasSize(3);
        assertThat(quotas).extracting(PlannedQuota::seq).containsExactly(1, 2, 3);
        assertThat(quotas).extracting(PlannedQuota::from).containsExactly(d("2026-01-01"), d("2026-02-01"), d("2026-03-01"));
        assertThat(quotas).extracting(PlannedQuota::to).containsExactly(d("2026-01-31"), d("2026-02-28"), d("2026-03-31"));
    }

    // PROVES: remainder goes to the last quota and the shares add up exactly (1000 / 3).
    @Test
    void remainderGoesToTheLastQuota() {
        var quotas = QuotaPlanner.plan(Periodicity.MONTHLY, d("2026-01-01"), d("2026-03-31"), qty("1000"), null);

        assertThat(quotas).extracting(PlannedQuota::qty)
                .containsExactly(qty("333.3333"), qty("333.3333"), qty("333.3334"));
        assertThat(sum(quotas)).isEqualByComparingTo("1000");
    }

    @Test
    void monthlyClipsPartialFirstAndLastMonths() {
        var quotas = QuotaPlanner.plan(Periodicity.MONTHLY, d("2026-01-15"), d("2026-03-10"), qty("300"), null);

        assertThat(quotas).extracting(PlannedQuota::from).containsExactly(d("2026-01-15"), d("2026-02-01"), d("2026-03-01"));
        assertThat(quotas).extracting(PlannedQuota::to).containsExactly(d("2026-01-31"), d("2026-02-28"), d("2026-03-10"));
    }

    @Test
    void weeklyUsesSevenDayBlocksWithAShorterLastBlock() {
        var quotas = QuotaPlanner.plan(Periodicity.WEEKLY, d("2026-01-01"), d("2026-01-17"), qty("300"), null);

        assertThat(quotas).hasSize(3);
        assertThat(quotas.get(0).to()).isEqualTo(d("2026-01-07"));
        assertThat(quotas.get(1).from()).isEqualTo(d("2026-01-08"));
        assertThat(quotas.get(2).from()).isEqualTo(d("2026-01-15"));
        assertThat(quotas.get(2).to()).isEqualTo(d("2026-01-17")); // shorter last block
    }

    @Test
    void dailyProducesOneQuotaPerDayInclusive() {
        var quotas = QuotaPlanner.plan(Periodicity.DAILY, d("2026-02-26"), d("2026-03-02"), qty("50"), null);

        assertThat(quotas).hasSize(5); // 26, 27, 28 Feb (2026 is not a leap year), 1, 2 Mar
        assertThat(quotas).allSatisfy(q -> assertThat(q.from()).isEqualTo(q.to()));
    }

    @Test
    void customUsesTheSuppliedPeriodsInDateOrder() {
        var quotas = QuotaPlanner.plan(Periodicity.CUSTOM, d("2026-01-01"), d("2026-06-30"), qty("100"), List.of(
                new CustomPeriod(d("2026-04-01"), d("2026-06-30"), qty("70")),
                new CustomPeriod(d("2026-01-01"), d("2026-01-31"), qty("30"))));

        assertThat(quotas).extracting(PlannedQuota::qty).containsExactly(qty("30"), qty("70"));
        assertThat(quotas).extracting(PlannedQuota::seq).containsExactly(1, 2);
    }

    @Test
    void customRejectsQuantityMismatchOverlapAndOutOfWindowPeriods() {
        LocalDate from = d("2026-01-01"), to = d("2026-06-30");
        assertThatThrownBy(() -> QuotaPlanner.plan(Periodicity.CUSTOM, from, to, qty("100"),
                List.of(new CustomPeriod(d("2026-01-01"), d("2026-01-31"), qty("30")))))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.details()).containsEntry("totalQty", "100.0000").containsEntry("periodsQty", "30.0000");
                });
        assertThatThrownBy(() -> QuotaPlanner.plan(Periodicity.CUSTOM, from, to, qty("100"), List.of(
                new CustomPeriod(d("2026-01-01"), d("2026-02-10"), qty("50")),
                new CustomPeriod(d("2026-02-10"), d("2026-03-31"), qty("50"))))).hasMessageContaining("overlap");
        assertThatThrownBy(() -> QuotaPlanner.plan(Periodicity.CUSTOM, from, to, qty("100"),
                List.of(new CustomPeriod(d("2026-01-01"), d("2026-07-31"), qty("100"))))).hasMessageContaining("outside");
    }

    @Test
    void rejectsBadDeliveryTermsAndTooSmallQuantities() {
        assertThatThrownBy(() -> QuotaPlanner.plan(Periodicity.MONTHLY, d("2026-03-01"), d("2026-01-01"), qty("10"), null))
                .isInstanceOf(DomainException.class).hasMessageContaining("before");
        assertThatThrownBy(() -> QuotaPlanner.plan(Periodicity.DAILY, d("2026-01-01"), d("2026-01-31"), qty("0.0010"), null))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.type()).isEqualTo("quantity-too-small"));
    }

    // PROVES the invariant sum(quota.qty) = trade.total_qty across many totals and period counts, not just examples.
    @Test
    void sharesAlwaysAddUpToTheTotal() {
        for (int parts = 1; parts <= 40; parts++) {
            for (String total : List.of("1", "7", "100", "1000.0001", "99999.9999", "12345.6789")) {
                var shares = QuantitySplitter.evenSplit(qty(total), parts);
                assertThat(shares).hasSize(parts);
                assertThat(shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(total);
                assertThat(shares).allSatisfy(s -> assertThat(s.signum()).isPositive());
            }
        }
    }
}
