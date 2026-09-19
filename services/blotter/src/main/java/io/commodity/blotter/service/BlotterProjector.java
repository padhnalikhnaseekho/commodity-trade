package io.commodity.blotter.service;

import io.commodity.blotter.domain.Provisional;
import io.commodity.blotter.domain.RowChange;
import io.commodity.blotter.domain.RowPatch;
import io.commodity.blotter.domain.RowView;
import io.commodity.blotter.repository.BlotterStore;
import io.commodity.blotter.repository.BlotterStore.RowState;
import io.commodity.contracts.events.PricingQuotaPublished;
import io.commodity.contracts.events.ValuationPublished;
import io.commodity.contracts.lookup.QuotaView;
import io.commodity.contracts.refs.AssignmentRef;
import io.commodity.contracts.valuation.SubjectLevel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Turns the events of other services into blotter rows and live row changes.
 *
 * <p>Two inputs: pricing's quota snapshots (priced, unpriced, approval per assignment) and the gateway's valuation results. Static data (desk, trade,
 * commodity) comes from the trade service through a cached port. For each event it writes the rows, then compares the quota's LIVE rows before and after and
 * offers the difference to the broadcaster.
 *
 * <p>WHY changes are offered only AFTER COMMIT: a change pushed to open blotters must describe committed state. Offering inside the transaction could announce
 * a row that then rolls back.
 *
 * <p>Idempotent: replaying an event, or the whole topic, rewrites the same rows and produces no further changes (an unchanged row diffs to nothing).
 */
@Service
public class BlotterProjector {

    private final BlotterStore store;
    private final CachedQuotaLookup quotas;
    private final RowBroadcaster broadcaster;

    public BlotterProjector(BlotterStore store, CachedQuotaLookup quotas, RowBroadcaster broadcaster) {
        this.store = store;
        this.quotas = quotas;
        this.broadcaster = broadcaster;
    }

    @Transactional
    public void onQuotaPublished(PricingQuotaPublished e) {
        QuotaView quota = quotas.find(e.quotaRef()).orElseThrow(() -> new IllegalStateException("snapshot for unknown quota " + e.quotaRef())); // retried, then DLQ
        Map<String, RowView> before = live(e.quotaRef());

        for (PricingQuotaPublished.Assignment a : e.assignments()) {
            store.upsertRow(new RowState(a.assignmentRef(), e.brd(), quota.tradeRef(), e.quotaRef(), quota.deskId(), quota.businessLine(), quota.commodity(),
                    a.qty(), a.pricedQty(), a.unpricedQty(), a.overFixed(), a.approvalStatus(), "ACTIVE", e.pqrId()));
        }
        // An assignment that was in force but is no longer listed has left the quota: record that as of this BRD so the row drops out of views from here on.
        List<String> listed = e.assignments().stream().map(PricingQuotaPublished.Assignment::assignmentRef).toList();
        for (RowView gone : before.values()) {
            if (!listed.contains(gone.assignmentRef())) {
                store.upsertRow(new RowState(gone.assignmentRef(), e.brd(), gone.tradeRef(), gone.quotaRef(), gone.deskId(), gone.businessLine(), gone.commodity(),
                        new BigDecimal(gone.qty()), new BigDecimal(gone.pricedQty()), new BigDecimal(gone.unpricedQty()), gone.overFixed(), gone.approvalStatus(),
                        "REMOVED", e.pqrId()));
            }
        }
        publishChanges(before, live(e.quotaRef()));
    }

    @Transactional
    public void onValuationPublished(ValuationPublished e) {
        String quotaRef = e.level() == SubjectLevel.QUOTA ? e.subjectRef() : AssignmentRef.parse(e.subjectRef()).quota().toString();
        Map<String, RowView> before = live(quotaRef);
        store.upsertValuation(e.subjectRef(), e.level().name(), e.brd(), new BigDecimal(e.value()), e.engine().name(), e.requestId(), e.completedAt());
        publishChanges(before, live(quotaRef));
    }

    /** The quota's rows in the LIVE view (latest of everything), with provisional derived, by assignment ref. */
    private Map<String, RowView> live(String quotaRef) {
        return Provisional.apply(store.rows(null, quotaRef, BlotterStore.LIVE)).stream().collect(Collectors.toMap(RowView::assignmentRef, Function.identity()));
    }

    private void publishChanges(Map<String, RowView> before, Map<String, RowView> after) {
        List<RowChange> changes = new ArrayList<>();
        after.forEach((ref, row) -> changes.add(RowPatch.diff(before.get(ref), row)));
        before.forEach((ref, row) -> { if (!after.containsKey(ref)) changes.add(RowPatch.diff(row, null)); });
        changes.removeIf(RowChange::isEmpty);
        afterCommit(() -> changes.forEach(broadcaster::offer));
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /** Rows as of a BRD for the API: desk-filtered, ordered by ref, with provisional derived from the current approval state. */
    @Transactional(readOnly = true)
    public List<RowView> rows(String deskId, LocalDate asOf) {
        return Provisional.apply(store.rows(deskId, null, asOf == null ? BlotterStore.LIVE : asOf));
    }
}
