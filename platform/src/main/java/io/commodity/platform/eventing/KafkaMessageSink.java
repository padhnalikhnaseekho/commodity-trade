package io.commodity.platform.eventing;

import io.commodity.platform.outbox.MessageSink;
import io.commodity.platform.outbox.OutboxRow;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Ships an outbox row to Kafka and waits for the broker's acknowledgement.
 *
 * <p>The envelope travels as Kafka HEADERS (eventId, occurredAt, brd, source, schemaVersion, traceparent), the JSON
 * body is the payload. Headers keep routing and dedup metadata out of the business schema, so an envelope change never
 * versions a payload.
 *
 * <p>WHY block on the acknowledgement: the publisher must only mark a row sent once Kafka has it. TRADEOFF: one round
 * trip per row; batching sends and awaiting them together is the obvious optimisation once throughput matters.
 * TARGET: Avro with a schema registry and BACKWARD compatibility; the demo uses JSON to avoid another container.
 */
public class KafkaMessageSink implements MessageSink {

    private final KafkaTemplate<String, String> kafka;

    public KafkaMessageSink(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void send(OutboxRow row) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.key(), row.payload());
        record.headers()
                .add("eventId", bytes(row.eventId().toString()))
                .add("occurredAt", bytes(row.occurredAt().toString()))
                .add("brd", bytes(row.brd().toString()))
                .add("source", bytes(row.source()))
                .add("schemaVersion", bytes(Integer.toString(row.schemaVersion())))
                .add("traceparent", bytes(row.traceparent()));
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
