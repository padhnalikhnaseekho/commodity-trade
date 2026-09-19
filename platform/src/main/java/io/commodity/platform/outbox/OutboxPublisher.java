package io.commodity.platform.outbox;

import java.sql.Timestamp;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polling publisher: reads unsent outbox rows in id order and ships them through a {@link MessageSink}.
 *
 * <p>Concurrency: {@code FOR UPDATE SKIP LOCKED} lets several publisher instances poll the same table without blocking
 * or double-sending a row. TRADEOFF: with more than one publisher, per-key ordering is no longer guaranteed (two
 * instances may ship rows of the same key out of order). The demo runs one publisher, so order is preserved; the
 * target relies on consumer-side version checks (aggregateVersion) rather than arrival order.
 *
 * <p>Failure: rows are marked sent only after the sink confirms. On the first failure the batch stops (preserving order),
 * rows already confirmed stay marked sent, and the failed row is retried on the next poll. A crash after the sink
 * confirmed but before the mark commits re-sends that row: at-least-once, hence consumer dedup on eventId.
 */
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    // Schema names are interpolated into SQL (identifiers cannot be bound parameters), so restrict them.
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String table;
    private final MessageSink sink;
    private final int batchSize;

    public OutboxPublisher(JdbcTemplate jdbc, TransactionTemplate tx, String schema, MessageSink sink, int batchSize) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.table = qualified(schema);
        this.sink = sink;
        this.batchSize = batchSize;
    }

    /** Ships up to one batch. Returns how many rows were confirmed and marked sent. */
    public int publishBatch() {
        Integer sent = tx.execute(status -> {
            List<OutboxRow> rows = jdbc.query(
                    "SELECT id, event_id, topic, msg_key, payload, brd, source, schema_version, traceparent, occurred_at"
                            + " FROM " + table + " WHERE sent_at IS NULL ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                    (rs, i) -> new OutboxRow(rs.getLong("id"), rs.getObject("event_id", java.util.UUID.class),
                            rs.getString("topic"), rs.getString("msg_key"), rs.getString("payload"),
                            rs.getObject("brd", java.time.LocalDate.class), rs.getString("source"),
                            rs.getInt("schema_version"), rs.getString("traceparent"),
                            rs.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant()),
                    batchSize);
            int count = 0;
            for (OutboxRow row : rows) {
                try {
                    sink.send(row);
                } catch (Exception e) {
                    log.warn("outbox delivery failed for {} (event {}); will retry", row.topic(), row.eventId(), e);
                    break;
                }
                jdbc.update("UPDATE " + table + " SET sent_at = ? WHERE id = ?", Timestamp.from(java.time.Instant.now()), row.id());
                count++;
            }
            return count;
        });
        return sent == null ? 0 : sent;
    }

    static String qualified(String schema) {
        if (!SAFE_IDENTIFIER.matcher(schema).matches()) throw new IllegalArgumentException("unsafe schema name: " + schema);
        return schema + ".outbox";
    }
}
