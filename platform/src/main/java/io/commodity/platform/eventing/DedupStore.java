package io.commodity.platform.eventing;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Consumer-side dedup on eventId, backed by the service's own {@code <schema>.processed_event} table.
 *
 * <p>WHY: the outbox delivers at-least-once, so a consumer WILL occasionally see an event twice. {@link #firstTime} is an
 * atomic "insert if absent": exactly one caller wins. Call it inside the same transaction that applies the event, so a
 * crash rolls back the marker together with the change, and the redelivery is processed for real.
 *
 * <p>TRADEOFF: a TTL sweep bounds the table, so an event redelivered after the TTL would be applied twice. The TTL must
 * comfortably exceed the broker's retention-plus-retry window. TARGET: the dedup set lives in a distributed cache with a TTL.
 */
public class DedupStore {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");

    private final JdbcTemplate jdbc;
    private final String table;

    public DedupStore(JdbcTemplate jdbc, String schema) {
        if (!SAFE_IDENTIFIER.matcher(schema).matches()) throw new IllegalArgumentException("unsafe schema name: " + schema);
        this.jdbc = jdbc;
        this.table = schema + ".processed_event";
    }

    /** True if this is the first time the event is seen (and records it); false for a duplicate. */
    public boolean firstTime(UUID eventId) {
        return jdbc.update("INSERT INTO " + table + " (event_id) VALUES (?) ON CONFLICT (event_id) DO NOTHING", eventId) == 1;
    }

    /** Deletes markers older than the TTL; returns how many were removed. */
    public int sweep(Duration olderThan) {
        return jdbc.update("DELETE FROM " + table + " WHERE processed_at < ?", Timestamp.from(Instant.now().minus(olderThan)));
    }
}
