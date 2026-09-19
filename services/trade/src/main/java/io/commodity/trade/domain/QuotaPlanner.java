package io.commodity.trade.domain;

import io.commodity.platform.error.DomainException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Derives quotas from a trade's delivery terms. This is where "a monthly schedule from 1 Jan to 31 Mar gives three
 * quotas" lives.
 *
 * <p>Rules (see docs/SPEC-ADDENDUM.md; the split rule is in the spec, the boundary rules were confirmed as assumptions):
 * <ul>
 *   <li>MONTHLY: calendar months clipped to [from, to]. 1 Jan to 31 Mar gives Jan, Feb, Mar.</li>
 *   <li>WEEKLY: consecutive 7-day blocks from {@code from}; the last block ends at {@code to} and may be shorter.</li>
 *   <li>DAILY: one quota per calendar day, inclusive.</li>
 *   <li>CUSTOM: the caller supplies explicit periods with quantities; they must sum to the total and not overlap.</li>
 *   <li>Every non-custom kind splits quantity evenly at scale 4, remainder to the last quota ({@link QuantitySplitter}).</li>
 * </ul>
 *
 * <p>INVARIANT: sum(quota.qty) = trade.total_qty (QuotaPlannerTest). Enforced here in domain code, deliberately not by a
 * database trigger, so the business rule stays visible and unit-testable with no database.
 */
public final class QuotaPlanner {

    /** One derived quota before it is given a ref and stored. {@code seq} starts at 1 and forms the quota ref suffix. */
    public record PlannedQuota(int seq, LocalDate from, LocalDate to, BigDecimal qty) {}

    /** A caller-supplied period for CUSTOM delivery. */
    public record CustomPeriod(LocalDate from, LocalDate to, BigDecimal qty) {}

    private QuotaPlanner() {}

    public static List<PlannedQuota> plan(Periodicity periodicity, LocalDate from, LocalDate to, BigDecimal totalQty,
                                          List<CustomPeriod> custom) {
        if (from == null || to == null) throw invalid("Delivery period start and end are required");
        if (to.isBefore(from)) throw invalid("Delivery period end is before its start").with("from", from).with("to", to);
        if (totalQty.signum() <= 0) throw invalid("Total quantity must be positive").with("totalQty", totalQty.toPlainString());

        return periodicity == Periodicity.CUSTOM
                ? planCustom(from, to, totalQty, custom)
                : planRegular(periodicity, from, to, totalQty);
    }

    private static List<PlannedQuota> planRegular(Periodicity periodicity, LocalDate from, LocalDate to, BigDecimal total) {
        List<LocalDate[]> periods = new ArrayList<>();
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            LocalDate end = switch (periodicity) {
                case MONTHLY -> min(to, cursor.with(TemporalAdjusters.lastDayOfMonth()));
                case WEEKLY -> min(to, cursor.plusDays(6));
                case DAILY -> cursor;
                case CUSTOM -> throw new IllegalStateException("handled by planCustom");
            };
            periods.add(new LocalDate[] {cursor, end});
            cursor = end.plusDays(1);
        }
        List<BigDecimal> shares = QuantitySplitter.evenSplit(total, periods.size());
        List<PlannedQuota> result = new ArrayList<>(periods.size());
        for (int i = 0; i < periods.size(); i++) {
            result.add(new PlannedQuota(i + 1, periods.get(i)[0], periods.get(i)[1], shares.get(i)));
        }
        return result;
    }

    private static List<PlannedQuota> planCustom(LocalDate from, LocalDate to, BigDecimal total, List<CustomPeriod> custom) {
        if (custom == null || custom.isEmpty()) throw invalid("CUSTOM delivery requires at least one explicit period");
        List<CustomPeriod> sorted = new ArrayList<>(custom);
        sorted.sort(Comparator.comparing(CustomPeriod::from));

        BigDecimal sum = BigDecimal.ZERO;
        CustomPeriod previous = null;
        for (CustomPeriod p : sorted) {
            if (p.to().isBefore(p.from())) throw invalid("Custom period end is before its start").with("from", p.from()).with("to", p.to());
            if (p.qty() == null || p.qty().signum() <= 0) throw invalid("Custom period quantity must be positive").with("from", p.from());
            if (p.from().isBefore(from) || p.to().isAfter(to)) {
                throw invalid("Custom period lies outside the delivery window").with("from", p.from()).with("to", p.to());
            }
            if (previous != null && !p.from().isAfter(previous.to())) {
                throw invalid("Custom periods overlap").with("first", previous.from() + ".." + previous.to()).with("second", p.from() + ".." + p.to());
            }
            sum = sum.add(p.qty());
            previous = p;
        }
        if (sum.compareTo(total) != 0) {
            throw new DomainException(409, "quota-quantity-mismatch", "Custom period quantities must add up to the trade quantity")
                    .with("totalQty", total.toPlainString()).with("periodsQty", sum.toPlainString());
        }
        List<PlannedQuota> result = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            CustomPeriod p = sorted.get(i);
            result.add(new PlannedQuota(i + 1, p.from(), p.to(), p.qty()));
        }
        return result;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static DomainException invalid(String title) {
        return new DomainException(400, "invalid-delivery-terms", title);
    }
}
