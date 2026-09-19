package io.commodity.blotter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.commodity.blotter.domain.RowChange;
import io.commodity.blotter.domain.RowView;
import io.commodity.blotter.repository.BlotterStore;
import io.commodity.contracts.events.PricingQuotaPublished;
import io.commodity.contracts.events.PricingQuotaPublished.Assignment;
import io.commodity.contracts.events.ValuationPublished;
import io.commodity.contracts.lookup.QuotaView;
import io.commodity.contracts.valuation.SubjectLevel;
import io.commodity.contracts.valuation.ValuationEngine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The blotter read model on a real Postgres (no Spring context): events in, rows and live changes out. The store's SQL, the as-of resolution and the
 * provisional derivation are all exercised for real. Each test uses its own quota, so tests are independent of order.
 */
class BlotterProjectorTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16").withStartupAttempts(5);
    private static final JdbcTemplate JDBC;
    private static final Map<String, QuotaView> QUOTAS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger(10);
    private static final LocalDate D1 = LocalDate.of(2026, 9, 10), D2 = LocalDate.of(2026, 9, 12), D3 = LocalDate.of(2026, 9, 14);

    static {
        POSTGRES.start();
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).schemas("blotter").locations("classpath:db/migration/blotter").load().migrate();
        JDBC = new JdbcTemplate(ds);
    }

    private final RowBroadcaster broadcaster = new RowBroadcaster(new ObjectMapper());
    private final List<String> pushed = new ArrayList<>();
    private final BlotterStore store = new BlotterStore(JDBC);
    private final BlotterProjector projector = new BlotterProjector(store, new CachedQuotaLookup(ref -> Optional.ofNullable(QUOTAS.get(ref))), broadcaster);

    /** A fresh quota on a fresh desk (so desk-filtered reads see only this test's rows). */
    private String quota(String businessLine) {
        int trade = NEXT.incrementAndGet();
        String ref = trade + ".1";
        QUOTAS.put(ref, new QuotaView(ref, String.valueOf(trade), "DESK-" + trade, businessLine, new BigDecimal("1000"), D1, null, "Copper concentrate"));
        broadcaster.subscribe(new RowBroadcaster.Subscriber() {
            @Override public String deskId() { return null; }
            @Override public boolean send(long id, String name, String json) { pushed.add(json); return true; }
        }, null);
        return ref;
    }

    private static String desk(String quotaRef) { return "DESK-" + quotaRef.split("\\.")[0]; }

    private static Assignment a(String ref, String qty, String priced, String approval) {
        BigDecimal q = new BigDecimal(qty), p = new BigDecimal(priced);
        return new Assignment(ref, q, p, q.subtract(p), false, approval);
    }

    private static PricingQuotaPublished snapshot(String quotaRef, LocalDate brd, Assignment... assignments) {
        return new PricingQuotaPublished(quotaRef, UUID.randomUUID(), brd, "REVISION", List.of(assignments));
    }

    private static ValuationPublished valuation(String subject, SubjectLevel level, LocalDate brd, String value) {
        return new ValuationPublished(UUID.randomUUID(), "sha256:x", subject, level, brd, ValuationEngine.MODERN, value, Instant.now());
    }

    private List<RowView> rows(String quotaRef, LocalDate asOf) { return projector.rows(desk(quotaRef), asOf); }

    // PROVES acceptance: the blotter shows priced and unpriced quantity per assignment (and the static data comes from trade).
    @Test
    void aSnapshotBecomesRowsWithPricedAndUnpricedQuantity() {
        String q = quota("CONCENTRATES");
        projector.onQuotaPublished(snapshot(q, D1, a(q + ".1", "100", "30", "UNAPPROVED"), a(q + ".2", "50", "0", "APPROVED")));

        List<RowView> rows = rows(q, null);
        assertThat(rows).extracting(RowView::assignmentRef).containsExactly(q + ".1", q + ".2");
        RowView first = rows.get(0);
        assertThat(first.pricedQty()).isEqualTo("30.0000");
        assertThat(first.unpricedQty()).isEqualTo("70.0000");
        assertThat(first.commodity()).isEqualTo("Copper concentrate");
        assertThat(first.deskId()).isEqualTo(desk(q));
        assertThat(first.tradeRef()).isEqualTo(q.split("\\.")[0]);
        assertThat(first.approvalStatus()).isEqualTo("UNAPPROVED");
        assertThat(first.status()).isEqualTo("ACTIVE");
        assertThat(first.valuation()).isNull();
        assertThat(rows.get(1).approvalStatus()).isEqualTo("APPROVED");
    }

    // PROVES the live push feed: a pricing change produces a change with ONLY the fields that moved.
    @Test
    void aPricingChangeProducesAMinimalRowChange() {
        String q = quota("BULK");
        projector.onQuotaPublished(snapshot(q, D1, a(q + ".1", "100", "0", "UNAPPROVED")));
        broadcaster.flush();
        pushed.clear();

        projector.onQuotaPublished(snapshot(q, D1, a(q + ".1", "100", "40", "UNAPPROVED")));
        broadcaster.flush();

        assertThat(pushed).hasSize(1);
        assertThat(pushed.get(0)).contains("/pricedQty").contains("40.0000").contains("/unpricedQty").contains("60.0000").doesNotContain("/qty");
    }

    // PROVES acceptance: the as-of control resolves a quota at a past BRD and shows the historical state, never a later one (monotonicity).
    @Test
    void asOfResolutionShowsTheHistoricalStateAndNeverALaterOne() {
        String q = quota("BULK");
        projector.onQuotaPublished(snapshot(q, D1, a(q + ".1", "100", "0", "UNAPPROVED")));
        projector.onQuotaPublished(snapshot(q, D2, a(q + ".1", "100", "25", "UNAPPROVED")));
        projector.onQuotaPublished(snapshot(q, D3, a(q + ".1", "100", "60", "APPROVED")));

        assertThat(rows(q, D1).get(0).pricedQty()).isEqualTo("0.0000");
        assertThat(rows(q, LocalDate.of(2026, 9, 11)).get(0).pricedQty()).isEqualTo("0.0000");   // between revisions: the earlier one is in force
        assertThat(rows(q, D2).get(0).pricedQty()).isEqualTo("25.0000");
        assertThat(rows(q, D3).get(0).pricedQty()).isEqualTo("60.0000");
        assertThat(rows(q, null).get(0).pricedQty()).isEqualTo("60.0000");                        // live
        assertThat(rows(q, LocalDate.of(2026, 9, 1))).isEmpty();                                   // before anything existed
        assertThat(rows(q, D1).get(0).approvalStatus()).isEqualTo("UNAPPROVED");                   // approval is historical too
    }

    // PROVES an assignment that leaves the quota drops out of views from that BRD on, but a past date still shows it, and open blotters are told.
    @Test
    void anAssignmentThatLeavesTheQuotaDropsOutFromThatDateOnward() {
        String q = quota("BULK");
        projector.onQuotaPublished(snapshot(q, D1, a(q + ".1", "100", "0", "UNAPPROVED"), a(q + ".2", "50", "0", "UNAPPROVED")));
        broadcaster.flush();
        pushed.clear();

        projector.onQuotaPublished(snapshot(q, D2, a(q + ".2", "50", "0", "UNAPPROVED")));   // .1 was cancelled
        broadcaster.flush();

        assertThat(rows(q, null)).extracting(RowView::assignmentRef).containsExactly(q + ".2");
        assertThat(rows(q, D1)).extracting(RowView::assignmentRef).containsExactly(q + ".1", q + ".2"); // history is intact
        assertThat(pushed).anySatisfy(json -> assertThat(json).contains(q + ".1").contains("\"remove\""));
    }

    // PROVES the provisional rule end to end: a valuation of an UNAPPROVED assignment shows as provisional; approving it flips the flag on the SAME valuation
    // with a live change (approval is not in the valuation's key, so nothing is recomputed).
    @Test
    void aValuationIsProvisionalUntilTheAssignmentIsApproved() {
        String q = quota("CONCENTRATES");
        String ref = q + ".1";
        projector.onQuotaPublished(snapshot(q, D1, a(ref, "100", "0", "UNAPPROVED")));
        projector.onValuationPublished(valuation(ref, SubjectLevel.ASSIGNMENT, D1, "10500.0000"));

        RowView valued = rows(q, null).get(0);
        assertThat(valued.valuation()).isEqualTo("10500.0000");
        assertThat(valued.valuationLevel()).isEqualTo("ASSIGNMENT");
        assertThat(valued.valuationEngine()).isEqualTo("MODERN");
        assertThat(valued.valuationAsOf()).isEqualTo(D1);
        assertThat(valued.provisional()).isTrue();
        broadcaster.flush();
        pushed.clear();

        projector.onQuotaPublished(new PricingQuotaPublished(q, UUID.randomUUID(), D1, "APPROVAL", List.of(a(ref, "100", "0", "APPROVED"))));
        broadcaster.flush();

        RowView approved = rows(q, null).get(0);
        assertThat(approved.valuation()).isEqualTo("10500.0000");   // same number
        assertThat(approved.provisional()).isFalse();                // now final
        assertThat(pushed).hasSize(1);
        assertThat(pushed.get(0)).contains("/approvalStatus").contains("/provisional").doesNotContain("/valuation\"");
    }

    // PROVES the owner's RM decision: a QUOTA-level valuation is shown on every assignment row of the quota, marked level QUOTA, and it is provisional while
    // ANY assignment of the quota is unapproved.
    @Test
    void aQuotaLevelValuationIsShownOnEveryRowAndIsProvisionalWhileAnyAssignmentIsUnapproved() {
        String q = quota("RM");
        projector.onQuotaPublished(snapshot(q, D1, a(q + ".1", "100", "0", "APPROVED"), a(q + ".2", "50", "0", "UNAPPROVED")));
        projector.onValuationPublished(valuation(q, SubjectLevel.QUOTA, D1, "99000.0000"));

        List<RowView> rows = rows(q, null);
        assertThat(rows).extracting(RowView::valuation).containsExactly("99000.0000", "99000.0000");
        assertThat(rows).extracting(RowView::valuationLevel).containsOnly("QUOTA");
        assertThat(rows).extracting(RowView::provisional).containsExactly(true, true);   // one unapproved assignment makes the whole number provisional

        projector.onQuotaPublished(new PricingQuotaPublished(q, UUID.randomUUID(), D1, "APPROVAL", List.of(a(q + ".1", "100", "0", "APPROVED"), a(q + ".2", "50", "0", "APPROVED"))));
        assertThat(rows(q, null)).extracting(RowView::provisional).containsExactly(false, false);
    }

    @Test
    void aValuationIsOnlyVisibleFromItsBrdOnward() {
        String q = quota("BULK");
        projector.onQuotaPublished(snapshot(q, D1, a(q + ".1", "100", "0", "APPROVED")));
        projector.onValuationPublished(valuation(q + ".1", SubjectLevel.ASSIGNMENT, D2, "5000.0000"));

        assertThat(rows(q, D1).get(0).valuation()).isNull();               // valued on D2: not yet visible on D1
        assertThat(rows(q, D2).get(0).valuation()).isEqualTo("5000.0000");
        assertThat(rows(q, D3).get(0).valuationAsOf()).isEqualTo(D2);       // still the latest valuation as of D3
    }

    // PROVES a result that arrives BEFORE its snapshot is not lost: valuations are stored apart from rows and joined on read.
    @Test
    void aValuationThatArrivesBeforeItsSnapshotIsNotLost() {
        String q = quota("BULK");
        projector.onValuationPublished(valuation(q + ".1", SubjectLevel.ASSIGNMENT, D1, "7000.0000"));
        projector.onQuotaPublished(snapshot(q, D1, a(q + ".1", "100", "0", "APPROVED")));

        assertThat(rows(q, null).get(0).valuation()).isEqualTo("7000.0000");
    }

    // PROVES idempotency: delivering the same events again (redelivery, or replaying the topic to rebuild) changes nothing and pushes nothing.
    @Test
    void replayingTheSameEventsChangesNothingAndPushesNothing() {
        String q = quota("BULK");
        var snap = snapshot(q, D1, a(q + ".1", "100", "30", "UNAPPROVED"));
        var val = valuation(q + ".1", SubjectLevel.ASSIGNMENT, D1, "3000.0000");
        projector.onQuotaPublished(snap);
        projector.onValuationPublished(val);
        broadcaster.flush();
        List<RowView> before = rows(q, null);
        pushed.clear();

        projector.onQuotaPublished(snap);
        projector.onValuationPublished(val);
        broadcaster.flush();

        assertThat(rows(q, null)).isEqualTo(before);
        assertThat(pushed).isEmpty();
    }

    @Test
    void aSnapshotForAnUnknownQuotaIsRejectedSoTheConsumerRetriesThenDeadLetters() {
        var unknown = snapshot("999999.1", D1, a("999999.1.1", "1", "0", "APPROVED"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> projector.onQuotaPublished(unknown)).isInstanceOf(IllegalStateException.class);
    }
}
