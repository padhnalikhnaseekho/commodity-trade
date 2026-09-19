package io.commodity.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.commodity.contracts.events.Topics;
import io.commodity.contracts.events.ValuationRequested;
import io.commodity.contracts.valuation.Lane;
import io.commodity.gateway.domain.LaneLimiter;
import io.commodity.gateway.repository.RequestStore;
import io.commodity.gateway.repository.RequestStore.RequestRow;
import io.commodity.platform.outbox.OutboxMessage;
import io.commodity.platform.outbox.OutboxWriter;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves PENDING requests to SENT as their lane has room: takes a slot, writes the request to the outbox for the engine, and marks the row SENT,
 * all in one transaction, so "sent" and "will be published" can never disagree.
 *
 * <p>Lane by lane, and only up to the lane's FREE slots. Requests beyond a lane's budget simply stay PENDING in the database (durable, not
 * in memory) until a slot frees, which is how a bulk backlog waits without ever holding an interactive request back.
 *
 * <p>Slots are taken BEFORE the transaction and any unused or failed ones are returned after it, so a rolled-back dispatch can never leak
 * a slot and shrink the lane's budget.
 */
public class Dispatcher {

    private static final String SOURCE = "valuation-gateway";

    private final RequestStore store;
    private final LaneLimiter limiter;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public Dispatcher(RequestStore store, LaneLimiter limiter, OutboxWriter outbox, TransactionTemplate tx, ObjectMapper json) {
        this.store = store;
        this.limiter = limiter;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
    }

    /** One pass over all lanes. Returns how many requests were sent. */
    public int dispatchOnce() {
        int total = 0;
        for (Lane lane : Lane.values()) total += dispatchLane(lane);
        return total;
    }

    private int dispatchLane(Lane lane) {
        int taken = 0;
        while (limiter.tryAcquire(lane)) taken++;
        if (taken == 0) return 0;
        final int slots = taken;

        int sent = 0;
        try {
            Integer n = tx.execute(status -> {
                int count = 0;
                for (RequestRow row : store.claimPending(lane, slots)) {
                    if (!store.markSent(row.requestId())) continue;
                    ValuationRequested request = read(row.payload()).withAttempt(row.attempts() + 1);
                    outbox.append(OutboxMessage.of(Topics.VALUATION_REQUEST, row.requestId().toString(), write(request), row.brd(), SOURCE));
                    count++;
                }
                return count;
            });
            sent = n == null ? 0 : n;
        } finally {
            for (int i = sent; i < slots; i++) limiter.release(lane); // return every slot that did not become an in-flight request
        }
        return sent;
    }

    private ValuationRequested read(String payload) {
        try {
            return json.readValue(payload, ValuationRequested.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored request is unreadable", e);
        }
    }

    private String write(ValuationRequested request) {
        try {
            return json.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise request", e);
        }
    }
}
