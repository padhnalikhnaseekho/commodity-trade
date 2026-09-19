package io.commodity.gateway.api;

import io.commodity.contracts.valuation.Lane;
import io.commodity.contracts.valuation.SubjectLevel;
import io.commodity.gateway.domain.RequestStatus;
import io.commodity.gateway.repository.RequestStore;
import io.commodity.gateway.repository.RequestStore.RequestRow;
import io.commodity.gateway.service.ValuationService;
import io.commodity.platform.error.DomainException;
import io.commodity.platform.error.Parse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST surface of the valuation gateway: submit, look up one request, browse requests.
 *
 * <p>Submit answers 202 for an accepted (or already in-flight) request, 200 with {@code cached: true} for an answer served from the cache,
 * and 409 for a closed BRD or unapproved assignments. The {@code cached} flag is deliberate: it makes idempotency observable in the demo
 * instead of something to take on trust. The browse endpoint is the request browser the legacy interface UI had, which clearly earned its keep.
 */
@RestController
@RequestMapping("/api/valuations")
public class ValuationController {

    public record SubmitRequest(String subjectRef, String subjectLevel, LocalDate brd, String lane, Boolean restatement) {}

    public record Accepted(UUID requestId, String requestKey, String status) {}

    public record CachedAnswer(UUID requestId, String requestKey, String status, JsonNode result, boolean cached) {}

    public record RequestView(UUID requestId, String requestKey, String subjectRef, String subjectLevel, LocalDate brd, String functionalLine,
                              String engine, String lane, String source, String status, int attempts, JsonNode result, String error,
                              Instant createdAt, Instant sentAt, Instant completedAt) {}

    private final ValuationService service;
    private final RequestStore store;
    private final ObjectMapper json;

    public ValuationController(ValuationService service, RequestStore store, ObjectMapper json) {
        this.service = service;
        this.store = store;
        this.json = json;
    }

    @PostMapping
    public ResponseEntity<?> submit(@RequestBody SubmitRequest body, @RequestHeader(value = "X-Caller", defaultValue = "api") String caller) throws Exception {
        if (body.subjectRef() == null || body.brd() == null) {
            throw new DomainException(400, "invalid-request", "subjectRef and brd are required");
        }
        var cmd = new ValuationService.Submit(body.subjectRef(), Parse.enumValue(SubjectLevel.class, body.subjectLevel(), "subjectLevel"), body.brd(),
                Parse.enumValue(Lane.class, body.lane(), "lane"), Boolean.TRUE.equals(body.restatement()), caller);
        return switch (service.submit(cmd)) {
            case ValuationService.Outcome.Accepted a ->
                    ResponseEntity.status(HttpStatus.ACCEPTED).body(new Accepted(a.requestId(), a.requestKey(), a.status().name()));
            case ValuationService.Outcome.Cached c ->
                    ResponseEntity.ok(new CachedAnswer(c.requestId(), c.requestKey(), RequestStatus.COMPLETED.name(), json.readTree(c.resultJson()), true));
        };
    }

    @GetMapping("/{requestId}")
    public RequestView get(@PathVariable UUID requestId) throws Exception {
        RequestRow row = store.findById(requestId).orElseThrow(() -> new DomainException(404, "not-found", "No such valuation request").with("requestId", requestId.toString()));
        return view(row);
    }

    @GetMapping
    public List<RequestView> browse(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate brd,
                                    @RequestParam(required = false) String status, @RequestParam(required = false) String lane) {
        RequestStatus st = status == null ? null : Parse.enumValue(RequestStatus.class, status, "status");
        Lane ln = lane == null ? null : Parse.enumValue(Lane.class, lane, "lane");
        return store.browse(brd, st, ln, 200).stream().map(r -> {
            try {
                return view(r);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    private RequestView view(RequestRow r) throws Exception {
        return new RequestView(r.requestId(), r.requestKey(), r.subjectRef(), r.subjectLevel().name(), r.brd(), r.functionalLine(), r.engine().name(),
                r.lane().name(), r.source(), r.status().name(), r.attempts(), r.result() == null ? null : json.readTree(r.result()), r.error(),
                r.createdAt(), r.sentAt(), r.completedAt());
    }
}
