package io.commodity.gateway.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.commodity.contracts.events.Topics;
import io.commodity.contracts.events.ValuationCompleted;
import io.commodity.gateway.service.ReplyHandler;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the shared reply topic {@code valuation.reply.v1}. Every gateway instance is in the same consumer group, so each reply reaches exactly
 * one instance, and that instance correlates it through the durable request table (see {@link ReplyHandler}). No per-instance reply queue is needed.
 *
 * <p>Failures follow the shared policy: retried, then parked on {@code valuation.reply.v1.dlq}; a malformed reply never stalls the partition.
 */
@Component
@ConditionalOnProperty("commodity.gateway.workers.enabled")
class ReplyListener {

    private final ReplyHandler handler;
    private final ObjectMapper json;

    ReplyListener(ReplyHandler handler, ObjectMapper json) {
        this.handler = handler;
        this.json = json;
    }

    @KafkaListener(topics = Topics.VALUATION_REPLY, groupId = "${commodity.gateway.consumer.group:valuation-gateway}")
    void onMessage(ConsumerRecord<String, String> record) throws Exception {
        handler.handle(json.readValue(record.value(), ValuationCompleted.class));
    }
}
