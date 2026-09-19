package io.commodity.pricing.service;

import io.commodity.contracts.lookup.BusinessDayClock;
import io.commodity.contracts.lookup.QuotaDirectory;
import io.commodity.contracts.lookup.QuotaView;
import io.commodity.contracts.refs.AssignmentRef;
import io.commodity.contracts.refs.QuotaRef;
import io.commodity.contracts.valuation.SubjectLevel;
import io.commodity.contracts.valuation.ValuationInputs;
import io.commodity.platform.error.DomainException;
import io.commodity.pricing.domain.*;
import io.commodity.pricing.repository.RevisionStore;
import io.commodity.pricing.repository.RevisionStore.QuotaRevisionRow;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pricing's application service: the user-driven writes (fix a price, add a parameter, approve) and the reads (resolve a quota
 * as of a BRD). Rules live in domain/; this class is the transaction boundary and the orchestration.
 *
 * <p>Every mutation is "read the head, change one assignment, write a new quota revision through the {@link RevisionWriter}".
 * WHY under a per-quota advisory lock: that is read-decide-write over the quota's chain. Without the lock two simultaneous
 * fixations could both pass the over-fixation check against the same head and together over-fix the assignment; the lock makes
 * the second see the first. (Captured requirement: sum(priced) &lt;= assignment quantity must hold under concurrency.)
 *
 * <p>Approval is NOT a revision: it is a separate insert-only record (V2 migration), so approving costs one row and never
 * enters the write-amplification path.
 */
@Service
public class PricingService {

    private final RevisionStore store;
    private final RevisionWriter writer;
    private final QuotaDirectory quotaDirectory;
    private final BusinessDayClock clock;
    private final JdbcTemplate jdbc;

    public PricingService(RevisionStore store, RevisionWriter writer, QuotaDirectory quotaDirectory, BusinessDayClock clock, JdbcTemplate jdbc) {
        this.store = store;
        this.writer = writer;
        this.quotaDirectory = quotaDirectory;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    public record Written(UUID itemId, UUID pqrId) {}

    /** A resolved quota plus per-assignment approval, ready to present. */
    public record PricingSnapshot(ResolvedQuota quota, java.util.Map<String, ApprovalStatus> approvals) {}

    /** Fixes (part of) an assignment's quantity at a price. Refused with 409 if it would exceed the assignment quantity. */
    @Transactional
    public Written addComponent(String assignmentRef, PriceComponentContent component) {
        String hash = ContentHasher.hash(component);
        UUID pqr = mutate(assignmentRef, a -> {
            FixationPolicy.checkCanFix(a, component.qty());
            return a.withComponent(component);
        });
        return new Written(store.componentId(store.assignmentRevisionIds(pqr).get(assignmentRef), hash).orElseThrow(), pqr);
    }

    @Transactional
    public Written addParameter(String assignmentRef, ParameterContent parameter) {
        String hash = ContentHasher.hash(parameter);
        UUID pqr = mutate(assignmentRef, a -> a.withParameter(parameter));
        return new Written(store.parameterId(store.assignmentRevisionIds(pqr).get(assignmentRef), hash).orElseThrow(), pqr);
    }

    /**
     * Records an approval decision. Insert-only: the latest record as of a BRD wins, so approving then un-approving is two rows
     * and the history is kept. The assignment must exist in pricing.
     */
    @Transactional
    public LocalDate approve(String assignmentRef, ApprovalStatus status) {
        String quotaRef = quotaOf(assignmentRef);
        QuotaView quota = quotaDirectory.find(quotaRef).orElseThrow(() -> notFound("quota", quotaRef));
        currentContent(quotaRef, assignmentRef); // 404 if pricing does not know the assignment
        LocalDate brd = clock.currentBrd(quota.deskId());
        store.insertApproval(assignmentRef, status, brd);
        return brd;
    }

    /** The quota's pricing as of a BRD (default: the desk's current BRD). */
    @Transactional(readOnly = true)
    public Optional<PricingSnapshot> resolve(String quotaRef, LocalDate asOf) {
        LocalDate date = asOf != null ? asOf : currentBrd(quotaRef);
        return store.asOf(quotaRef, date).map(row -> {
            var contents = store.loadAssignments(row.pqrId());
            var approvals = new java.util.LinkedHashMap<String, ApprovalStatus>();
            contents.forEach(c -> approvals.put(c.assignmentRef(), store.approvalAsOf(c.assignmentRef(), date)));
            return new PricingSnapshot(new ResolvedQuota(row.pqrId(), quotaRef, row.qagrId(), row.brd(), contents), approvals);
        });
    }

    /** All revisions of a quota, oldest first: the replay list. */
    @Transactional(readOnly = true)
    public List<QuotaRevisionRow> revisions(String quotaRef) {
        return store.revisions(quotaRef);
    }

    /**
     * Assembles a valuation's inputs from the revision in force as of a BRD: the immutable ids the request key is built from,
     * plus quantities, components, parameters and approval. Empty when pricing has nothing for the subject at that date.
     * For ASSIGNMENT level the subject is an assignment ref, for QUOTA level a quota ref (all its assignments).
     */
    @Transactional(readOnly = true)
    public Optional<ValuationInputs> valuationInputs(String subjectRef, SubjectLevel level, LocalDate asOf) {
        String quotaRef = level == SubjectLevel.QUOTA ? subjectRef : quotaOf(subjectRef);
        QuotaView quota = quotaDirectory.find(quotaRef).orElseThrow(() -> notFound("quota", quotaRef));
        return store.asOf(quotaRef, asOf).flatMap(row -> {
            var assignments = store.loadInputs(row.pqrId(), asOf);
            if (level == SubjectLevel.ASSIGNMENT) {
                assignments = assignments.stream().filter(a -> a.assignmentRef().equals(subjectRef)).toList();
                if (assignments.isEmpty()) return Optional.empty();
            }
            return Optional.of(new ValuationInputs(subjectRef, level, asOf, row.pqrId(), row.qagrId(), quota.businessLine(), assignments));
        });
    }

    /** Whether pricing holds any price component for the assignment (backs the FixationDirectory port used by logistics). */
    @Transactional(readOnly = true)
    public boolean hasFixation(String assignmentRef) {
        return currentContent(quotaOf(assignmentRef), assignmentRef).hasFixation();
    }

    // ---- internals ------------------------------------------------------------------------------------------------

    private UUID mutate(String assignmentRef, UnaryOperator<AssignmentContent> change) {
        String quotaRef = quotaOf(assignmentRef);
        QuotaView quota = quotaDirectory.find(quotaRef).orElseThrow(() -> notFound("quota", quotaRef));
        lockQuota(quotaRef); // lock BEFORE reading the head, so the state we validate cannot change under us

        QuotaRevisionRow head = store.head(quotaRef).orElseThrow(() -> notFound("pricing for quota", quotaRef));
        List<AssignmentContent> current = store.loadAssignments(head.pqrId());
        AssignmentContent target = current.stream().filter(a -> a.assignmentRef().equals(assignmentRef)).findFirst()
                .orElseThrow(() -> notFound("assignment", assignmentRef));

        AssignmentContent changed = change.apply(target);
        List<AssignmentContent> next = current.stream().map(a -> a == target ? changed : a).toList();

        LocalDate brd = clock.currentBrd(quota.deskId());
        BrdGuard.requireOpen(quotaRef, brd, brd); // a user-driven change is always on the desk's open BRD
        // The revision keeps pointing at the same logistics revision: a price change does not change the physical graph.
        return writer.write(QuotaRef.parse(quotaRef), head.qagrId(), brd, next).pqrId();
    }

    private AssignmentContent currentContent(String quotaRef, String assignmentRef) {
        QuotaRevisionRow head = store.head(quotaRef).orElseThrow(() -> notFound("assignment", assignmentRef));
        return store.loadAssignments(head.pqrId()).stream().filter(a -> a.assignmentRef().equals(assignmentRef)).findFirst()
                .orElseThrow(() -> notFound("assignment", assignmentRef));
    }

    private LocalDate currentBrd(String quotaRef) {
        QuotaView quota = quotaDirectory.find(quotaRef).orElseThrow(() -> notFound("quota", quotaRef));
        return clock.currentBrd(quota.deskId());
    }

    void lockQuota(String quotaRef) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {}, "pricing.quota:" + quotaRef);
    }

    static String quotaOf(String assignmentRef) {
        try {
            return AssignmentRef.parse(assignmentRef).quota().toString();
        } catch (IllegalArgumentException e) {
            throw new DomainException(400, "invalid-ref", "Not an assignment ref").with("ref", assignmentRef);
        }
    }

    static DomainException notFound(String what, String ref) {
        return new DomainException(404, "not-found", "No such " + what).with("ref", ref);
    }
}
