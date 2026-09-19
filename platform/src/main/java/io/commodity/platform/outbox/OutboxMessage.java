package io.commodity.platform.outbox;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One event waiting to be published: destination, key, JSON body, and the envelope fields that travel as Kafka headers
 * (eventId, brd, source, schemaVersion; occurredAt is stamped by the database, traceparent is added if absent).
 *
 * @param key the Kafka partition key. Ordering is per key only, so choose the aggregate id (quotaRef for the revision
 *            chain, assignmentRef for assignment-scoped events). Never a dealId: a desk-default deal would put a whole
 *            desk on one partition.
 */
public record OutboxMessage(UUID eventId, String topic, String key, String payloadJson, LocalDate brd, String source,
                            int schemaVersion, String traceparent) {

    /** Message with a fresh eventId, schemaVersion 1 and a generated trace context. */
    public static OutboxMessage of(String topic, String key, String payloadJson, LocalDate brd, String source) {
        return new OutboxMessage(UUID.randomUUID(), topic, key, payloadJson, brd, source, 1, null);
    }
}
