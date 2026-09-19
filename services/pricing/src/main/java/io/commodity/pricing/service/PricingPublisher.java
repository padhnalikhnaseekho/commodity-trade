package io.commodity.pricing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.commodity.contracts.events.PricingQuotaPublished;
import io.commodity.contracts.events.Topics;
import io.commodity.platform.outbox.OutboxMessage;
import io.commodity.platform.outbox.OutboxWriter;
import io.commodity.pricing.domain.ApprovalStatus;
import io.commodity.pricing.domain.AssignmentContent;
import io.commodity.pricing.repository.RevisionStore;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Publishes a quota's pricing snapshot ({@code pricing.quota.published.v1}) through the outbox, in the caller's transaction.
 *
 * <p>Called after every pricing revision and every approval decision. The snapshot is the FULL quota (all assignments with priced, unpriced and
 * approval), so a consumer never reads back, and it is keyed by quotaRef so all snapshots of one quota arrive in order.
 *
 * <p>Priced, unpriced and over-fixed are DERIVED here from the content and sent; pricing still never stores them.
 */
@Component
public class PricingPublisher {

    private static final String SOURCE = "pricing";

    private final OutboxWriter outbox;
    private final RevisionStore store;
    private final ObjectMapper json;

    public PricingPublisher(@Qualifier("pricingOutboxWriter") OutboxWriter outbox, RevisionStore store, ObjectMapper json) {
        this.outbox = outbox;
        this.store = store;
        this.json = json;
    }

    /** @param cause REVISION (pricing content changed) or APPROVAL (an approval decision, same revision) */
    public void publish(String quotaRef, UUID pqrId, LocalDate brd, String cause, List<AssignmentContent> contents) {
        List<PricingQuotaPublished.Assignment> assignments = contents.stream().map(a -> new PricingQuotaPublished.Assignment(a.assignmentRef(), a.qty(),
                a.pricedQty(), a.unpricedQty(), a.overFixed(), store.approvalAsOf(a.assignmentRef(), brd).name())).toList();
        try {
            String payload = json.writeValueAsString(new PricingQuotaPublished(quotaRef, pqrId, brd, cause, assignments));
            outbox.append(OutboxMessage.of(Topics.PRICING_QUOTA_PUBLISHED, quotaRef, payload, brd, SOURCE));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise pricing snapshot", e);
        }
    }

    /** Keeps the enum import used by callers that build approvals by name. */
    static String name(ApprovalStatus s) {
        return s.name();
    }
}
