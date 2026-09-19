package io.commodity.pricing.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.commodity.contracts.events.QagRevisionEvent;
import io.commodity.contracts.events.Topics;
import io.commodity.pricing.service.QagRevisionHandler;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code logistics.qag.revision.v1} and hands each event to {@link QagRevisionHandler}.
 *
 * <p>Failure handling lives in the container's error handler (see PricingConfig): a failing record is retried three times with a
 * fixed back-off, then published to {@code <topic>.dlq} and the partition moves on. A poison message therefore never stalls the
 * partition. The listener itself only parses and delegates; it holds no state.
 *
 * <p>Ordering: the topic is keyed by quotaRef, so all revisions of one quota arrive in order on one partition. Events for
 * different quotas may interleave, which is fine: pricing revisions of different quotas are independent.
 */
@Component
@ConditionalOnProperty("commodity.pricing.consumer.enabled")
class QagRevisionListener {

    private static final Logger log = LoggerFactory.getLogger(QagRevisionListener.class);

    private final QagRevisionHandler handler;
    private final ObjectMapper json;

    QagRevisionListener(QagRevisionHandler handler, ObjectMapper json) {
        this.handler = handler;
        this.json = json;
    }

    @KafkaListener(topics = Topics.QAG_REVISION, groupId = "${commodity.pricing.consumer.group:pricing}")
    void onMessage(ConsumerRecord<String, String> record) throws Exception {
        Header header = record.headers().lastHeader("eventId");
        if (header == null) throw new IllegalArgumentException("message has no eventId header (offset " + record.offset() + ")");
        UUID eventId = UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));

        QagRevisionEvent event = json.readValue(record.value(), QagRevisionEvent.class); // malformed JSON throws -> retry -> DLQ
        var result = handler.handle(eventId, event);
        log.debug("qag revision {} for quota {}: {}", event.qagrId(), event.quotaRef(), result);
    }
}
