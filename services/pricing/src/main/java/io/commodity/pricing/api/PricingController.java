package io.commodity.pricing.api;

import io.commodity.contracts.valuation.SubjectLevel;
import io.commodity.contracts.valuation.ValuationInputs;
import io.commodity.platform.error.DomainException;
import io.commodity.platform.error.Parse;
import io.commodity.pricing.domain.*;
import io.commodity.pricing.service.PricingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * REST surface of the pricing service. Thin: parse and validate input, delegate, shape output. Derived values (priced,
 * unpriced, over-fixed) are computed here from the resolved content and never stored.
 */
@RestController
@RequestMapping("/api")
public class PricingController {

    private final PricingService service;

    public PricingController(PricingService service) {
        this.service = service;
    }

    public record Created(UUID id, UUID pqrId) {}
    public record ComponentCreated(UUID pcId, UUID pqrId) {}
    public record ParameterCreated(UUID pprId, UUID pqrId) {}
    public record ApprovalView(String assignmentRef, String status, LocalDate brd) {}
    public record ParameterView(String element, String value) {}
    public record ComponentView(String kind, String qty, String fixedPrice, String indexName, LocalDate periodFrom,
                                LocalDate periodTo, String formula, boolean provisional) {}
    public record AssignmentView(String assignmentRef, String qty, String pricedQty, String unpricedQty, boolean overFixed,
                                 String approvalStatus, List<ParameterView> parameters, List<ComponentView> components) {}
    public record PricingView(UUID pqrId, LocalDate brd, UUID qagrId, List<AssignmentView> assignments) {}
    public record RevisionView(UUID pqrId, LocalDate brd, UUID qagrId, Instant createdAt) {}

    @PostMapping("/assignments/{assignmentRef}/price-components")
    @ResponseStatus(HttpStatus.CREATED)
    public ComponentCreated addComponent(@PathVariable String assignmentRef, @RequestBody PricingRequests.Component body) {
        var component = new PriceComponentContent(Parse.enumValue(ComponentKind.class, body.kind(), "kind"), decimal(body.qty(), "qty"),
                body.fixedPrice() == null ? null : decimal(body.fixedPrice(), "fixedPrice"), body.indexName(), body.periodFrom(),
                body.periodTo(), body.formula(), Boolean.TRUE.equals(body.provisional()));
        var written = service.addComponent(assignmentRef, component);
        return new ComponentCreated(written.itemId(), written.pqrId());
    }

    @PostMapping("/assignments/{assignmentRef}/parameters")
    @ResponseStatus(HttpStatus.CREATED)
    public ParameterCreated addParameter(@PathVariable String assignmentRef, @RequestBody PricingRequests.Parameter body) {
        var written = service.addParameter(assignmentRef, new ParameterContent(body.element(), decimal(body.value(), "value")));
        return new ParameterCreated(written.itemId(), written.pqrId());
    }

    @PostMapping("/assignments/{assignmentRef}/approval")
    @ResponseStatus(HttpStatus.CREATED)
    public ApprovalView approve(@PathVariable String assignmentRef, @RequestBody PricingRequests.Approval body) {
        ApprovalStatus status = Parse.enumValue(ApprovalStatus.class, body.status(), "status");
        return new ApprovalView(assignmentRef, status.name(), service.approve(assignmentRef, status));
    }

    @GetMapping("/assignments/{assignmentRef}/fixation")
    public PricingRequests.Fixation fixation(@PathVariable String assignmentRef) {
        return new PricingRequests.Fixation(assignmentRef, service.hasFixation(assignmentRef));
    }

    @GetMapping("/quotas/{quotaRef}/pricing")
    public PricingView pricing(@PathVariable String quotaRef,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        var snap = service.resolve(quotaRef, asOf).orElseThrow(() -> new DomainException(404, "not-found", "No pricing as of that date")
                .with("quotaRef", quotaRef).with("asOf", String.valueOf(asOf)));
        var q = snap.quota();
        return new PricingView(q.pqrId(), q.brd(), q.qagrId(), q.assignments().stream().map(a -> view(a, snap.approvals().get(a.assignmentRef()))).toList());
    }

    /**
     * The inputs of a valuation as of a BRD (backs the ValuationInputsProvider port used by the gateway). Every input carries the
     * id of the immutable row it came from, so the same request always resolves to the same ids.
     */
    @GetMapping("/valuation-inputs")
    public ValuationInputs valuationInputs(@RequestParam String subjectRef, @RequestParam String subjectLevel,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        SubjectLevel level = Parse.enumValue(SubjectLevel.class, subjectLevel, "subjectLevel");
        return service.valuationInputs(subjectRef, level, asOf).orElseThrow(() -> new DomainException(404, "not-found",
                "No pricing for the subject as of that date").with("subjectRef", subjectRef).with("asOf", asOf.toString()));
    }

    @GetMapping("/quotas/{quotaRef}/revisions")
    public List<RevisionView> revisions(@PathVariable String quotaRef) {
        return service.revisions(quotaRef).stream().map(r -> new RevisionView(r.pqrId(), r.brd(), r.qagrId(), r.createdAt())).toList();
    }

    private static AssignmentView view(AssignmentContent a, ApprovalStatus approval) {
        return new AssignmentView(a.assignmentRef(), a.qty().toPlainString(), a.pricedQty().toPlainString(), a.unpricedQty().toPlainString(),
                a.overFixed(), approval.name(),
                a.parameters().stream().map(p -> new ParameterView(p.element(), p.value().toPlainString())).toList(),
                a.components().stream().map(c -> new ComponentView(c.kind().name(), c.qty().toPlainString(),
                        c.fixedPrice() == null ? null : c.fixedPrice().toPlainString(), c.indexName(), c.periodFrom(), c.periodTo(),
                        c.formula(), c.provisional())).toList());
    }

    private static BigDecimal decimal(String text, String field) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException | NullPointerException e) {
            throw new DomainException(400, "invalid-decimal", "Not a number").with("field", field).with("value", String.valueOf(text));
        }
    }
}
