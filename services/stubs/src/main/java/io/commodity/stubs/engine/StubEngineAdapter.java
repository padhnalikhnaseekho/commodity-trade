package io.commodity.stubs.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.commodity.contracts.events.Topics;
import io.commodity.contracts.events.ValuationCompleted;
import io.commodity.contracts.events.ValuationRequested;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * STUB of the valuation engine's adapter: consumes {@code valuation.request.v1}, "computes", replies on {@code valuation.reply.v1}.
 *
 * <p>THE PROPERTY THIS CLASS EXISTS TO SHOW: the request is SELF-CONTAINED, so this adapter performs NO lookups against logistics, quality or
 * pricing, and this module depends on no other service (its only dependencies are the shared contracts and platform). The legacy interface read
 * the same sources a second time and could disagree with its caller; here one system assembles the inputs and the engine only computes.
 *
 * <p>The reply is scheduled after the lane's delay instead of sleeping, so the listener thread never blocks: a slow bulk request cannot delay an
 * interactive one behind it on this adapter. (Head-of-line blocking is the gateway's lane budget to prevent, and it must not be masked or
 * caused down here.)
 *
 * <p>LEGACY/TARGET: the real adapter translates JSON to the engine's wire format and back. And it replies with a direct send, not through an
 * outbox: the adapter has no database, so there is no business change for an outbox row to be atomic with. The gateway's watchdog retries any
 * request whose reply is lost, so a lost reply costs a retry, not a wrong answer.
 */
public class StubEngineAdapter {

    private static final Logger log = LoggerFactory.getLogger(StubEngineAdapter.class);

    private final EngineSettings settings;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final ScheduledExecutorService scheduler;

    public StubEngineAdapter(EngineSettings settings, KafkaTemplate<String, String> kafka, ObjectMapper json, ScheduledExecutorService scheduler) {
        this.settings = settings;
        this.kafka = kafka;
        this.json = json;
        this.scheduler = scheduler;
    }

    @KafkaListener(topics = Topics.VALUATION_REQUEST, groupId = "${commodity.engine.consumer.group:engine-adapter}")
    public void onRequest(ConsumerRecord<String, String> record) throws Exception {
        ValuationRequested request = json.readValue(record.value(), ValuationRequested.class);
        long delayMs = StubEngine.delay(request.lane().name(), settings.delaysMs()).toMillis();
        scheduler.schedule(() -> reply(request), delayMs, TimeUnit.MILLISECONDS);
    }

    private void reply(ValuationRequested request) {
        try {
            ValuationCompleted reply = StubEngine.shouldFail(request.requestId(), request.attempt(), settings.failureRate(), settings.failAttempts())
                    ? new ValuationCompleted(request.requestId(), request.requestKey(), request.engine(), false, null, "stub engine failure (attempt " + request.attempt() + ")")
                    : new ValuationCompleted(request.requestId(), request.requestKey(), request.engine(), true,
                            StubEngine.value(request.inputs().qty(), request.subjectRef(), settings.basePrice()).toPlainString(), null);
            kafka.send(Topics.VALUATION_REPLY, request.requestId().toString(), json.writeValueAsString(reply));
        } catch (Exception e) {
            log.error("could not reply for request {}", request.requestId(), e); // the gateway's watchdog will retry it
        }
    }
}
