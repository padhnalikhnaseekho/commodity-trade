package io.commodity.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.commodity.contracts.events.ValuationRequested;
import io.commodity.contracts.lookup.BusinessDayClock;
import io.commodity.contracts.lookup.FunctionalLine;
import io.commodity.contracts.lookup.FunctionalLineDirectory;
import io.commodity.contracts.lookup.QuotaDirectory;
import io.commodity.contracts.lookup.QuotaView;
import io.commodity.contracts.refs.AssignmentRef;
import io.commodity.contracts.valuation.Lane;
import io.commodity.contracts.valuation.SubjectLevel;
import io.commodity.contracts.valuation.ValuationInputs;
import io.commodity.contracts.valuation.ValuationInputsProvider;
import io.commodity.gateway.domain.RequestKey;
import io.commodity.gateway.domain.RequestStatus;
import io.commodity.gateway.repository.RequestStore;
import io.commodity.platform.error.DomainException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts a valuation request: the front door of the gateway.
 *
 * <p>Flow (the order is deliberate):
 * <ol>
 *   <li>Assemble the inputs from pricing's immutable revisions as of the BRD; refuse if any assignment is not APPROVED (approval gates
 *       eligibility for valuation and P&amp;L).</li>
 *   <li>Derive the deterministic request key.</li>
 *   <li>CACHE: a COMPLETED request with that key returns immediately, marked {@code cached: true}. This comes BEFORE the closed-BRD check, so
 *       replaying a past date is a cache hit rather than an error or a recomputation.</li>
 *   <li>IN FLIGHT: an identical request already PENDING or SENT is returned instead of starting a second one.</li>
 *   <li>BRD: refuse a date earlier than the desk's current BRD (closed), unless the caller flags a restatement.</li>
 *   <li>ROUTE by the FunctionalLine from reference data, insert PENDING with the complete request. The dispatcher sends it when its lane has room.</li>
 * </ol>
 *
 * <p>WHY a lock on the request key: steps 3 to 6 are check-then-insert. Two identical submissions arriving together would both see "nothing
 * there" and both start work. Serialising on the key (per-key advisory lock, released at commit) makes the second one see the first, so
 * idempotency holds under concurrency, not just in sequence.
 */
@Service
public class ValuationService {

    /** What the caller asked for. {@code restatement} allows writing for a closed BRD (a deliberate restatement of history). */
    public record Submit(String subjectRef, SubjectLevel level, LocalDate brd, Lane lane, boolean restatement, String source) {}

    /** Result of a submission: a new or in-flight request, or an already-computed answer from the cache. */
    public sealed interface Outcome {
        record Accepted(UUID requestId, String requestKey, RequestStatus status) implements Outcome {}
        record Cached(UUID requestId, String requestKey, String resultJson) implements Outcome {}
    }

    private final ValuationInputsProvider inputsProvider;
    private final QuotaDirectory quotas;
    private final FunctionalLineDirectory lines;
    private final BusinessDayClock clock;
    private final RequestStore store;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    public ValuationService(ValuationInputsProvider inputsProvider, QuotaDirectory quotas, FunctionalLineDirectory lines, BusinessDayClock clock,
                            RequestStore store, ObjectMapper json, JdbcTemplate jdbc) {
        this.inputsProvider = inputsProvider;
        this.quotas = quotas;
        this.lines = lines;
        this.clock = clock;
        this.store = store;
        this.json = json;
        this.jdbc = jdbc;
    }

    @Transactional
    public Outcome submit(Submit cmd) {
        String quotaRef = cmd.level() == SubjectLevel.QUOTA ? cmd.subjectRef() : quotaOf(cmd.subjectRef());
        QuotaView quota = quotas.find(quotaRef).orElseThrow(() -> notFound("quota", quotaRef));
        FunctionalLine line = lines.resolve(quota.businessLine(), quota.createdBrd(), quota.functionalLine());

        ValuationInputs inputs = inputsProvider.assemble(cmd.subjectRef(), cmd.level(), cmd.brd())
                .orElseThrow(() -> new DomainException(404, "no-pricing", "No pricing for the subject as of that date")
                        .with("subjectRef", cmd.subjectRef()).with("brd", cmd.brd().toString()));
        requireApproved(inputs);

        // marketDataAsOf: the market data date the valuation is struck against. No market data exists in P0, so it is the BRD.
        String key = RequestKey.of(inputs, line.name(), cmd.brd());
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {}, "gateway.key:" + key);

        var cached = store.findCompletedByKey(key);
        if (cached.isPresent()) return new Outcome.Cached(cached.get().requestId(), key, cached.get().result());

        var inFlight = store.findInFlightByKey(key);
        if (inFlight.isPresent()) return new Outcome.Accepted(inFlight.get().requestId(), key, inFlight.get().status());

        LocalDate deskBrd = clock.currentBrd(quota.deskId());
        if (!cmd.restatement() && cmd.brd().isBefore(deskBrd)) {
            throw new DomainException(409, "brd-closed", "The business date is closed for the desk; flag a restatement to value it anyway")
                    .with("deskId", quota.deskId()).with("brd", cmd.brd().toString()).with("deskBrd", deskBrd.toString());
        }

        UUID requestId = UUID.randomUUID();
        var payload = new ValuationRequested(requestId, key, cmd.subjectRef(), cmd.level(), cmd.brd(), line.name(), line.engine(), cmd.lane(),
                0, inputs.pqrId(), cmd.level() == SubjectLevel.ASSIGNMENT ? inputs.assignments().get(0).parId() : null, flatten(inputs));
        store.insertPending(requestId, key, cmd.subjectRef(), cmd.level(), cmd.brd(), line.name(), line.engine(), cmd.lane(), cmd.source(), toJson(payload));
        return new Outcome.Accepted(requestId, key, RequestStatus.PENDING);
    }

    /** All assignments must be approved: every one of them contributes to the value. Lists the offenders so the caller can act. */
    private static void requireApproved(ValuationInputs inputs) {
        List<String> unapproved = inputs.assignments().stream().filter(a -> !a.approved()).map(ValuationInputs.AssignmentInputs::assignmentRef).toList();
        if (!unapproved.isEmpty()) {
            throw new DomainException(409, "assignment-not-approved", "Assignments must be approved to be eligible for valuation")
                    .with("subjectRef", inputs.subjectRef()).with("unapproved", unapproved.stream().collect(Collectors.joining(",")));
        }
    }

    /** One flat view of the inputs for the engine: total quantity, and the components and parameters of every assignment. */
    private static ValuationRequested.Inputs flatten(ValuationInputs in) {
        var qty = in.assignments().stream().map(ValuationInputs.AssignmentInputs::qty).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        return new ValuationRequested.Inputs(qty, in.assignments().stream().flatMap(a -> a.components().stream()).toList(),
                in.assignments().stream().flatMap(a -> a.parameters().stream()).toList());
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise request", e);
        }
    }

    private static String quotaOf(String assignmentRef) {
        try {
            return AssignmentRef.parse(assignmentRef).quota().toString();
        } catch (IllegalArgumentException e) {
            throw new DomainException(400, "invalid-ref", "Not an assignment ref").with("ref", assignmentRef);
        }
    }

    private static DomainException notFound(String what, String ref) {
        return new DomainException(404, "not-found", "No such " + what).with("ref", ref);
    }
}
