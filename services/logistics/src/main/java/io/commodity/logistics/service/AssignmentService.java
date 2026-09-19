package io.commodity.logistics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.commodity.contracts.events.ChangeKind;
import io.commodity.contracts.events.QagRevisionEvent;
import io.commodity.contracts.events.ShippingOperationalEvent;
import io.commodity.contracts.events.Topics;
import io.commodity.contracts.lookup.BusinessDayClock;
import io.commodity.contracts.lookup.FixationDirectory;
import io.commodity.contracts.lookup.QuotaDirectory;
import io.commodity.contracts.lookup.QuotaView;
import io.commodity.contracts.refs.AssignmentRef;
import io.commodity.logistics.domain.*;
import io.commodity.logistics.repository.*;
import io.commodity.platform.error.DomainException;
import io.commodity.platform.outbox.OutboxMessage;
import io.commodity.platform.outbox.OutboxWriter;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The logistics write path: creates and changes assignments, cuts QAG revisions, and records the events, atomically.
 *
 * <p>Every public method is ONE transaction that covers: the assignment change, the new revision (if material), and the
 * outbox row(s). Either all of it happens or none of it does (a refused change leaves no revision and no event).
 *
 * <p>WHY a per-quota advisory lock: the revision chain (previous_id), the assignment ref sequence and the quota-quantity
 * cap are all "read, decide, write" over the same quota. Two concurrent requests would each read the same head and both
 * write, forking the chain or over-allocating. {@code pg_advisory_xact_lock} serialises writers of ONE quota and releases
 * at commit, while different quotas proceed in parallel. TRADEOFF: writers to the same quota queue; acceptable because a
 * quota's assignment changes are low frequency, and it is far simpler than optimistic retry loops.
 *
 * <p>Material vs operational: only material changes cut a revision. That classification comes from the caller-supplied
 * {@link ChangeKind}, checked here. Operational events go to a separate high-volume topic and never touch the chain,
 * which is what keeps the revision count near ten per trade.
 */
@Service
public class AssignmentService {

    private static final String SOURCE = "logistics";

    private final AssignmentRepository assignments;
    private final QagRevisionRepository revisions;
    private final QagRevisionMemberRepository members;
    private final QuotaDirectory quotaDirectory;
    private final FixationDirectory fixations;
    private final BusinessDayClock clock;
    private final OutboxWriter outbox;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    public AssignmentService(AssignmentRepository assignments, QagRevisionRepository revisions,
                             QagRevisionMemberRepository members, QuotaDirectory quotaDirectory, FixationDirectory fixations, BusinessDayClock clock,
                             OutboxWriter outbox, ObjectMapper json, JdbcTemplate jdbc) {
        this.assignments = assignments;
        this.revisions = revisions;
        this.members = members;
        this.quotaDirectory = quotaDirectory;
        this.fixations = fixations;
        this.clock = clock;
        this.outbox = outbox;
        this.json = json;
        this.jdbc = jdbc;
    }

    public record Added(String assignmentRef, UUID qagrId) {}

    /** Creates an assignment inside a quota and cuts a QAG revision whose diff is exactly that ref. */
    @Transactional
    public Added addAssignment(String quotaRef, BigDecimal qty, ChangeKind kind) {
        requireMaterial(kind);
        QuotaView quota = quotaDirectory.find(quotaRef).orElseThrow(() -> notFound("quota", quotaRef));
        lockQuota(quotaRef);

        List<Assignment> existing = assignments.findByQuotaRefOrderBySeq(quotaRef);
        QuotaQuantityPolicy.check(quotaRef, quota.qty(), activeQty(existing, null), qty);

        // Refs are never reused: max(seq)+1 over ALL assignments, whatever their status.
        int seq = existing.stream().mapToInt(Assignment::getSeq).max().orElse(0) + 1;
        String ref = AssignmentRef.parse(quotaRef + "." + seq).toString();
        assignments.save(new Assignment(ref, quotaRef, seq, qty, AssignmentStatus.ACTIVE));

        return new Added(ref, cutRevision(quota, kind).getQagrId());
    }

    /**
     * Changes an assignment's quantity and/or status.
     *
     * @return the id of the revision cut, or of the unchanged head when nothing effectively changed (a no-op cuts nothing)
     */
    @Transactional
    public UUID modify(String assignmentRef, BigDecimal newQty, AssignmentStatus newStatus, ChangeKind kind) {
        requireMaterial(kind);
        if (newQty == null && newStatus == null) {
            throw new DomainException(400, "invalid-request", "Nothing to change: supply qty and/or status");
        }
        String quotaRef = quotaOf(assignmentRef);
        QuotaView quota = quotaDirectory.find(quotaRef).orElseThrow(() -> notFound("quota", quotaRef));
        lockQuota(quotaRef); // lock BEFORE reading, so the state we decide on cannot change under us

        Assignment a = assignments.findByAssignmentRef(assignmentRef).orElseThrow(() -> notFound("assignment", assignmentRef));
        if (a.getStatus() != AssignmentStatus.ACTIVE) {
            throw new DomainException(409, "assignment-not-active", "Only ACTIVE assignments can be changed")
                    .with("assignmentRef", assignmentRef).with("status", a.getStatus().name());
        }

        BigDecimal qty = newQty != null ? newQty : a.getQty();
        AssignmentStatus status = newStatus != null ? newStatus : a.getStatus();
        boolean changes = qty.compareTo(a.getQty()) != 0 || status != a.getStatus();
        if (!changes) {
            return revisions.findFirstByQuotaRefOrderByIdDesc(quotaRef).orElseThrow().getQagrId();
        }
        // Owner rule: while an assignment has a price fixation its quantity cannot be edited. Fixations belong to pricing,
        // so we ask through a port. Checked only when the quantity really changes.
        if (qty.compareTo(a.getQty()) != 0 && fixations.hasFixation(assignmentRef)) {
            throw new DomainException(409, "assignment-has-fixation", "Quantity cannot be edited while the assignment has a price fixation")
                    .with("assignmentRef", assignmentRef).with("currentQty", a.getQty().toPlainString())
                    .with("requestedQty", qty.toPlainString());
        }
        if (status == AssignmentStatus.ACTIVE) { // still holding quantity: the cap applies to the new value
            List<Assignment> all = assignments.findByQuotaRefOrderBySeq(quotaRef);
            QuotaQuantityPolicy.check(quotaRef, quota.qty(), activeQty(all, a), qty);
        }
        a.changeQty(qty);
        a.changeStatus(status);
        return cutRevision(quota, kind).getQagrId();
    }

    /**
     * Records an operational event (load, discharge, bill of lading, insurance transfer). It is published on the
     * operational topic and cuts NO revision: these are frequent and cannot move a valuation.
     */
    @Transactional
    public void recordOperational(String assignmentRef, ChangeKind kind) {
        if (kind.isMaterial()) {
            throw new DomainException(400, "change-kind-not-operational", "Material changes must go through the assignment endpoints")
                    .with("changeKind", kind.name());
        }
        String quotaRef = quotaOf(assignmentRef);
        QuotaView quota = quotaDirectory.find(quotaRef).orElseThrow(() -> notFound("quota", quotaRef));
        assignments.findByAssignmentRef(assignmentRef).orElseThrow(() -> notFound("assignment", assignmentRef));

        var brd = clock.currentBrd(quota.deskId());
        outbox.append(OutboxMessage.of(Topics.SHIPPING_OPERATIONAL, assignmentRef,
                toJson(new ShippingOperationalEvent(assignmentRef, quotaRef, kind, brd)), brd, SOURCE));
    }

    /** Latest revision as of a BRD, plus its members (numeric order). */
    @Transactional(readOnly = true)
    public Optional<RevisionWithMembers> revisionAsOf(String quotaRef, java.time.LocalDate asOf) {
        return revisions.findByQuotaRefAndBrdLessThanEqualOrderByBrdDescCreatedAtDesc(quotaRef, asOf).stream().findFirst()
                .map(r -> new RevisionWithMembers(r, sortedMembers(r.getQagrId())));
    }

    /** Desk BRD for a quota (used as the default "as of" for reads). */
    public java.time.LocalDate currentBrd(String quotaRef) {
        QuotaView quota = quotaDirectory.find(quotaRef).orElseThrow(() -> notFound("quota", quotaRef));
        return clock.currentBrd(quota.deskId());
    }

    public record RevisionWithMembers(QagRevision revision, List<QagRevisionMember> members) {}

    // ---- internals ------------------------------------------------------------------------------------------------

    /** Cuts the next revision: diff against the head, persist revision + members, record the event. */
    private QagRevision cutRevision(QuotaView quota, ChangeKind kind) {
        String quotaRef = quota.quotaRef();
        Optional<QagRevision> head = revisions.findFirstByQuotaRefOrderByIdDesc(quotaRef);

        Map<String, BigDecimal> before = head.map(h -> memberMap(sortedMembers(h.getQagrId()))).orElseGet(LinkedHashMap::new);
        // Membership = ACTIVE assignments only (owner decision). A status change away from ACTIVE therefore shows as "removed".
        Map<String, BigDecimal> after = new LinkedHashMap<>();
        assignments.findByQuotaRefOrderBySeq(quotaRef).stream().filter(x -> x.getStatus() == AssignmentStatus.ACTIVE)
                .forEach(x -> after.put(x.getAssignmentRef(), x.getQty()));

        QagDiff diff = QagDiff.between(before, after);
        var brd = clock.currentBrd(quota.deskId());
        UUID qagrId = UUID.randomUUID();
        UUID previous = head.map(QagRevision::getQagrId).orElse(null);

        QagRevision revision = revisions.save(new QagRevision(qagrId, quotaRef, previous, brd, new String[] {kind.name()}));
        after.forEach((ref, qty) -> members.save(new QagRevisionMember(new QagRevisionMemberId(qagrId, ref), qty)));

        var memberList = after.keySet().stream().map(AssignmentRef::parse).sorted()
                .map(r -> new QagRevisionEvent.Member(r.toString(), after.get(r.toString()))).toList();
        var event = new QagRevisionEvent(qagrId, previous, quotaRef, brd, List.of(kind), diff.added(), diff.removed(),
                diff.modified(), memberList);
        // Same transaction as the change above: the event exists if and only if the revision does.
        outbox.append(OutboxMessage.of(Topics.QAG_REVISION, quotaRef, toJson(event), brd, SOURCE));
        return revision;
    }

    private void lockQuota(String quotaRef) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {}, "logistics.quota:" + quotaRef);
    }

    private List<QagRevisionMember> sortedMembers(UUID qagrId) {
        return members.findByIdQagrId(qagrId).stream()
                .sorted(Comparator.comparing(m -> AssignmentRef.parse(m.getId().assignmentRef()))).toList();
    }

    private static Map<String, BigDecimal> memberMap(List<QagRevisionMember> list) {
        return list.stream().collect(Collectors.toMap(m -> m.getId().assignmentRef(), QagRevisionMember::getQty,
                (a, b) -> a, LinkedHashMap::new));
    }

    /** Sum of ACTIVE assignment quantities, optionally excluding the assignment being changed. */
    private static BigDecimal activeQty(List<Assignment> all, Assignment exclude) {
        return all.stream().filter(x -> x.getStatus() == AssignmentStatus.ACTIVE && x != exclude)
                .map(Assignment::getQty).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void requireMaterial(ChangeKind kind) {
        if (!kind.isMaterial()) {
            throw new DomainException(400, "change-kind-not-material", "Operational changes do not cut a QAG revision")
                    .with("changeKind", kind.name());
        }
    }

    private static String quotaOf(String assignmentRef) {
        try {
            return AssignmentRef.parse(assignmentRef).quota().toString();
        } catch (IllegalArgumentException e) {
            throw new DomainException(400, "invalid-ref", "Not an assignment ref").with("ref", assignmentRef);
        }
    }

    private String toJson(Object event) {
        try {
            return json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise event", e);
        }
    }

    private static DomainException notFound(String what, String ref) {
        return new DomainException(404, "not-found", "No such " + what).with("ref", ref);
    }
}
