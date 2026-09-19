package io.commodity.logistics.api;

import io.commodity.contracts.events.ChangeKind;
import io.commodity.logistics.domain.AssignmentStatus;
import io.commodity.logistics.domain.QagRevisionMember;
import io.commodity.logistics.service.AssignmentService;
import io.commodity.platform.error.DomainException;
import io.commodity.platform.error.Parse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * REST surface of the logistics write path. Thin on purpose: parse and validate input, delegate, shape the response.
 * All business rules live in AssignmentService and domain/.
 */
@RestController
@RequestMapping("/api")
public class AssignmentController {

    private final AssignmentService service;

    public AssignmentController(AssignmentService service) {
        this.service = service;
    }

    public record Created(String assignmentRef, UUID qagrId) {}

    public record Changed(UUID qagrId) {}

    public record MemberView(String assignmentRef, String qty) {}

    public record QagView(UUID qagrId, UUID previousQagrId, LocalDate brd, List<String> changeKinds, List<MemberView> members) {}

    @PostMapping("/quotas/{quotaRef}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public Created add(@PathVariable String quotaRef, @RequestBody AssignmentRequests.Add body) {
        var added = service.addAssignment(quotaRef, qty(body.qty()), kind(body.changeKind()));
        return new Created(added.assignmentRef(), added.qagrId());
    }

    /** Material change: cuts a revision (or returns the unchanged head if the request changes nothing). */
    @PatchMapping("/assignments/{assignmentRef}")
    public Changed patch(@PathVariable String assignmentRef, @RequestBody AssignmentRequests.Patch body) {
        BigDecimal qty = body.qty() == null ? null : qty(body.qty());
        AssignmentStatus status = body.status() == null ? null : Parse.enumValue(AssignmentStatus.class, body.status(), "status");
        return new Changed(service.modify(assignmentRef, qty, status, kind(body.changeKind())));
    }

    /** Operational event: 202 Accepted, published on the operational topic, never cuts a revision. */
    @PostMapping("/assignments/{assignmentRef}/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void operational(@PathVariable String assignmentRef, @RequestBody AssignmentRequests.OperationalEvent body) {
        service.recordOperational(assignmentRef, kind(body.changeKind()));
    }

    /** The revision in force for a quota as of a BRD (default: the desk's current BRD). */
    @GetMapping("/quotas/{quotaRef}/qag")
    public QagView qag(@PathVariable String quotaRef,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        LocalDate date = asOf != null ? asOf : service.currentBrd(quotaRef);
        var found = service.revisionAsOf(quotaRef, date)
                .orElseThrow(() -> new DomainException(404, "not-found", "No QAG revision as of that date")
                        .with("quotaRef", quotaRef).with("asOf", date.toString()));
        var r = found.revision();
        return new QagView(r.getQagrId(), r.getPreviousId(), r.getBrd(), Arrays.asList(r.getChangeKinds()),
                found.members().stream().map(AssignmentController::view).toList());
    }

    private static MemberView view(QagRevisionMember m) {
        return new MemberView(m.getId().assignmentRef(), m.getQty().toPlainString());
    }

    private static ChangeKind kind(String text) {
        if (text == null) throw new DomainException(400, "invalid-request", "changeKind is required").with("field", "changeKind");
        return Parse.enumValue(ChangeKind.class, text, "changeKind");
    }

    private static BigDecimal qty(String text) {
        try {
            BigDecimal v = new BigDecimal(text);
            if (v.signum() <= 0 || v.stripTrailingZeros().scale() > 4) throw new NumberFormatException();
            return v.setScale(4);
        } catch (NumberFormatException | NullPointerException e) {
            throw new DomainException(400, "invalid-quantity", "Quantity must be a positive number with at most 4 decimals")
                    .with("value", String.valueOf(text));
        }
    }
}
