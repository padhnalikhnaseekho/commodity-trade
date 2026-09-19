package io.commodity.platform.outbox;

import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Records an event in the service's own {@code <schema>.outbox} table, in the SAME database transaction as the business
 * change that caused it.
 *
 * <p>WHY the outbox and never a direct KafkaTemplate.send from a service method: a direct send after commit can be lost
 * (crash between commit and send), and a send before commit can announce a change that then rolls back. Writing the
 * event as a row in the same transaction makes "state changed" and "event will be published" a single atomic fact. A
 * separate publisher ({@link OutboxPublisher}) ships it afterwards. Delivery is therefore at-least-once, and consumers
 * dedup on eventId.
 *
 * <p>TARGET vs demo: the target reads the outbox with CDC (Debezium) or, where a licence is unavailable, a polling
 * publisher. The demo uses the polling publisher: higher latency, entirely adequate, and it adds no container.
 */
public class OutboxWriter {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final String table;

    public OutboxWriter(JdbcTemplate jdbc, String schema) {
        this.jdbc = jdbc;
        this.table = OutboxPublisher.qualified(schema);
    }

    /** Must be called inside the caller's transaction. */
    public void append(OutboxMessage m) {
        jdbc.update("INSERT INTO " + table
                        + " (event_id, topic, msg_key, payload, brd, source, schema_version, traceparent)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                m.eventId(), m.topic(), m.key(), m.payloadJson(), m.brd(), m.source(), m.schemaVersion(),
                m.traceparent() != null ? m.traceparent() : newTraceparent());
    }

    /**
     * W3C trace context: version-traceId-spanId-flags. Generated here so every event has one.
     * TARGET: taken from the active OpenTelemetry span, so async request-reply stays traceable end to end.
     */
    static String newTraceparent() {
        byte[] trace = new byte[16];
        byte[] span = new byte[8];
        RANDOM.nextBytes(trace);
        RANDOM.nextBytes(span);
        HexFormat hex = HexFormat.of();
        return "00-" + hex.formatHex(trace) + "-" + hex.formatHex(span) + "-01";
    }
}
