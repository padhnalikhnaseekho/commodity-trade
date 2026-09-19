package io.commodity.platform.outbox;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** An outbox row as read by the publisher (message plus its database id and the time the event occurred). */
public record OutboxRow(long id, UUID eventId, String topic, String key, String payload, LocalDate brd, String source,
                        int schemaVersion, String traceparent, Instant occurredAt) {}
