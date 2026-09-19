package io.commodity.pricing.service;

import io.commodity.contracts.lookup.BusinessDayClock;
import io.commodity.contracts.lookup.QuotaDirectory;
import io.commodity.contracts.lookup.QuotaView;
import io.commodity.contracts.valuation.Lane;
import io.commodity.contracts.valuation.SubjectLevel;
import io.commodity.contracts.valuation.ValuationSubmitter;
import io.commodity.platform.error.DomainException;
import io.commodity.pricing.repository.RevisionStore;
import io.commodity.pricing.repository.RevisionStore.QuotaRevisionRow;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replay: re-request valuation from a stored pricing revision, for support investigation, break resolution or reprocessing after a fix.
 *
 * <p>READ-ONLY with respect to revisions: it never writes a new one (ReplayTest asserts the revision count is unchanged). If a replayed result ever
 * differed from the stored one, that would be a bug, and it would be visible, because replay asks the gateway with inputs derived from the same
 * immutable revision ids.
 *
 * <p>WHY replay is a cache hit and not a recomputation: the gateway's request key is built from immutable revision ids, so a request that was already
 * answered has the same key and is served from the result cache with {@code cached: true}. That is the cleanest demonstration that the idempotency
 * design works. Replay is sent with the restatement flag, because reproducing a past business date is precisely a restatement of history; a closed
 * BRD is only refused for NEW work, never for an answer already computed.
 *
 * <p>Two entry points (both captured): by quota ref (the latest revision of that quota) or by a specific revision id.
 * Valuation level follows the business line: RM values at quota level, every other line at assignment level (captured default).
 */
@Service
public class ReplayService {

    /** One valuation request made by a replay. */
    public record Replayed(String subjectRef, UUID requestId, String requestKey, String status, boolean cached) {}

    public record Result(String quotaRef, UUID pqrId, java.time.LocalDate brd, List<Replayed> requests) {}

    private final RevisionStore store;
    private final QuotaDirectory quotas;
    private final BusinessDayClock clock;
    private final PricingService pricing;
    private final ObjectProvider<ValuationSubmitter> gateway;

    public ReplayService(RevisionStore store, QuotaDirectory quotas, BusinessDayClock clock, PricingService pricing, ObjectProvider<ValuationSubmitter> gateway) {
        this.store = store;
        this.quotas = quotas;
        this.clock = clock;
        this.pricing = pricing;
        this.gateway = gateway;
    }

    @Transactional(readOnly = true)
    public Result replayQuota(String quotaRef) {
        return replay(store.head(quotaRef).orElseThrow(() -> notFound("pricing for quota", quotaRef)));
    }

    @Transactional(readOnly = true)
    public Result replayRevision(UUID pqrId) {
        return replay(store.byPqrId(pqrId).orElseThrow(() -> notFound("pricing revision", pqrId.toString())));
    }

    private Result replay(QuotaRevisionRow revision) {
        ValuationSubmitter submitter = gateway.getIfAvailable();
        if (submitter == null) throw new DomainException(503, "valuation-unavailable", "No valuation gateway is configured");

        // The gateway resolves inputs by business date. A revision that has been superseded ON THE SAME BRD would resolve to the newer one,
        // so replaying the older one could silently value different content. Refuse loudly instead of answering for the wrong revision.
        var inForce = store.asOf(revision.quotaRef(), revision.brd()).orElseThrow();
        if (!inForce.pqrId().equals(revision.pqrId())) {
            throw new DomainException(409, "revision-superseded-on-brd", "A newer revision exists on the same business date; replay the newer one")
                    .with("requestedPqrId", revision.pqrId().toString()).with("inForcePqrId", inForce.pqrId().toString()).with("brd", revision.brd().toString());
        }

        QuotaView quota = quotas.find(revision.quotaRef()).orElseThrow(() -> notFound("quota", revision.quotaRef()));
        List<Replayed> requests = "RM".equals(quota.businessLine())
                ? List.of(submit(submitter, revision.quotaRef(), SubjectLevel.QUOTA, revision))
                : store.loadAssignments(revision.pqrId()).stream()
                        .map(a -> submit(submitter, a.assignmentRef(), SubjectLevel.ASSIGNMENT, revision)).toList();
        return new Result(revision.quotaRef(), revision.pqrId(), revision.brd(), requests);
    }

    private Replayed submit(ValuationSubmitter submitter, String subjectRef, SubjectLevel level, QuotaRevisionRow revision) {
        var s = submitter.submit(subjectRef, level, revision.brd(), Lane.BULK, true, "pricing-replay"); // BULK: reprocessing must not compete with traders
        return new Replayed(subjectRef, s.requestId(), s.requestKey(), s.status(), s.cached());
    }

    private static DomainException notFound(String what, String ref) {
        return new DomainException(404, "not-found", "No such " + what).with("ref", ref);
    }
}
