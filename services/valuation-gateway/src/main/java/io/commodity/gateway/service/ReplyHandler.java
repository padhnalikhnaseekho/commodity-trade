package io.commodity.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.commodity.contracts.events.Topics;
import io.commodity.contracts.events.ValuationCompleted;
import io.commodity.contracts.events.ValuationPublished;
import io.commodity.gateway.domain.LaneLimiter;
import io.commodity.gateway.repository.RequestStore;
import io.commodity.platform.outbox.OutboxMessage;
import io.commodity.platform.outbox.OutboxWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies an engine reply to its request.
 *
 * <p>Correlation is through the DATABASE, by requestId, never through an in-memory map. That single choice is what makes the gateway
 * stateless and restart-safe: any instance can handle any reply, and a gateway that was killed mid-flight completes the request from durable
 * state when it comes back. It is the difference between a demo and a toy.
 *
 * <p>Idempotent by construction: the transition is compare-and-set on status SENT. A duplicate reply, or a reply that arrives after the
 * watchdog already retried or failed the request, changes nothing (zero rows) and does not touch the lane's slot count.
 * An error reply is treated like a timeout: another attempt while the budget lasts, then FAILED.
 */
@Service
public class ReplyHandler {

    private static final Logger log = LoggerFactory.getLogger(ReplyHandler.class);
    /** Total sends allowed per request (first send plus retries). Past this, FAILED. */
    public static final int MAX_ATTEMPTS = 3;

    private final RequestStore store;
    private final LaneLimiter limiter;
    private final ObjectMapper json;
    private final OutboxWriter outbox;

    public ReplyHandler(RequestStore store, LaneLimiter limiter, ObjectMapper json, @Qualifier("gatewayOutboxWriter") OutboxWriter outbox) {
        this.store = store;
        this.limiter = limiter;
        this.json = json;
        this.outbox = outbox;
    }

    @Transactional
    public void handle(ValuationCompleted reply) {
        if (reply.success()) {
            String result = toJson(Map.of("value", reply.result(), "engine", reply.engine().name()));
            store.complete(reply.requestId(), result).ifPresentOrElse(done -> {
                limiter.release(done.lane());
                // Result fan-out, in the same transaction as the COMPLETED transition: published if and only if the request really completed.
                outbox.append(OutboxMessage.of(Topics.VALUATION_PUBLISHED, done.subjectRef(), toJson(new ValuationPublished(reply.requestId(), done.requestKey(),
                        done.subjectRef(), done.level(), done.brd(), done.engine(), reply.result(), java.time.Instant.now())), done.brd(), "valuation-gateway"));
            }, () -> log.info("ignored reply for {}: not SENT any more (duplicate or late)", reply.requestId()));
        } else {
            store.failOrRetry(reply.requestId(), reply.error(), MAX_ATTEMPTS).ifPresentOrElse(t -> limiter.release(t.lane()),
                    () -> log.info("ignored error reply for {}: not SENT any more", reply.requestId()));
        }
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise result", e);
        }
    }
}
