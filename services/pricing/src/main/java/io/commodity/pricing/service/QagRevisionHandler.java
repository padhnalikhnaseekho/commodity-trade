package io.commodity.pricing.service;

import io.commodity.contracts.events.QagRevisionEvent;
import io.commodity.contracts.lookup.QuotaDirectory;
import io.commodity.contracts.lookup.QuotaView;
import io.commodity.contracts.refs.QuotaRef;
import io.commodity.platform.eventing.DedupStore;
import io.commodity.pricing.domain.AssignmentContent;
import io.commodity.pricing.domain.BrdGuard;
import io.commodity.pricing.domain.EventApplier;
import io.commodity.pricing.repository.RevisionStore;
import io.commodity.contracts.lookup.BusinessDayClock;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a QAG revision event to pricing: one event, one new pricing revision, pinned to that QAG revision's id.
 *
 * <p>ONE transaction covers the dedup marker, the quota lock, and the revision write. WHY that matters: delivery is
 * at-least-once, so an event can arrive twice. The marker and the revision commit together, so a crash between them cannot
 * leave "marked processed but never applied" (the event would be lost) or "applied but not marked" (it would apply twice).
 *
 * <p>Uses the event's {@code members} as the truth for what exists (no read-back to logistics) and lets the writer's content hashing
 * decide what is shared. A revision is written for every QAG revision even when no pricing content changed (for example a title
 * transfer): pricing pins to QAG revisions, and with structural sharing that costs only the quota revision and its member links.
 */
@Service
public class QagRevisionHandler {

    public enum Result { APPLIED, DUPLICATE }

    private final DedupStore dedup;
    private final QuotaDirectory quotaDirectory;
    private final BusinessDayClock clock;
    private final RevisionStore store;
    private final RevisionWriter writer;
    private final JdbcTemplate jdbc;

    public QagRevisionHandler(DedupStore dedup, QuotaDirectory quotaDirectory, BusinessDayClock clock, RevisionStore store,
                              RevisionWriter writer, JdbcTemplate jdbc) {
        this.dedup = dedup;
        this.quotaDirectory = quotaDirectory;
        this.clock = clock;
        this.store = store;
        this.writer = writer;
        this.jdbc = jdbc;
    }

    @Transactional
    public Result handle(UUID eventId, QagRevisionEvent event) {
        if (!dedup.firstTime(eventId)) return Result.DUPLICATE;

        String quotaRef = event.quotaRef();
        QuotaView quota = quotaDirectory.find(quotaRef)
                .orElseThrow(() -> new IllegalStateException("QAG revision for unknown quota " + quotaRef)); // retried, then DLQ
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {}, "pricing.quota:" + quotaRef);
        BrdGuard.requireOpen(quotaRef, event.brd(), clock.currentBrd(quota.deskId()));

        List<AssignmentContent> current = store.head(quotaRef).map(h -> store.loadAssignments(h.pqrId())).orElse(List.of());
        writer.write(QuotaRef.parse(quotaRef), event.qagrId(), event.brd(), EventApplier.apply(current, event));
        return Result.APPLIED;
    }
}
