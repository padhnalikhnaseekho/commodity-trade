package io.commodity.gateway.repository;

import io.commodity.contracts.valuation.Lane;
import io.commodity.contracts.valuation.SubjectLevel;
import io.commodity.contracts.valuation.ValuationEngine;
import io.commodity.gateway.domain.RequestStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * All SQL for the durable request state. Every state change is a compare-and-set on the current status (UPDATE ... WHERE status = ...):
 * whoever wins the transition acts on it, everyone else sees zero rows and does nothing. That makes duplicate replies, a watchdog racing a
 * late reply, and several instances safe without in-memory coordination.
 */
public class RequestStore {

    /** A row of gateway.valuation_request. {@code payload} is the complete, self-contained request as JSON. */
    public record RequestRow(UUID requestId, String requestKey, String subjectRef, SubjectLevel subjectLevel, LocalDate brd,
                             String functionalLine, ValuationEngine engine, Lane lane, String source, RequestStatus status, int attempts,
                             String payload, String result, String error, Instant createdAt, Instant sentAt, Instant completedAt, int version,
                             boolean provisionalAtRequest) {}

    /** Outcome of a fail-or-retry transition: the lane whose slot to release, and whether it went back to PENDING or on to FAILED. */
    public record Transition(Lane lane, RequestStatus status) {}

    private static final String COLUMNS = "request_id, request_key, subject_ref, subject_level, brd, functional_line, engine, lane, source,"
            + " status, attempts, payload::text, result::text, error, created_at, sent_at, completed_at, version, provisional";

    private final JdbcTemplate jdbc;

    public RequestStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static RequestRow row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new RequestRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), SubjectLevel.valueOf(rs.getString(4)),
                rs.getObject(5, LocalDate.class), rs.getString(6), ValuationEngine.valueOf(rs.getString(7)), Lane.valueOf(rs.getString(8)),
                rs.getString(9), RequestStatus.valueOf(rs.getString(10)), rs.getInt(11), rs.getString(12), rs.getString(13), rs.getString(14),
                instant(rs, 15), instant(rs, 16), instant(rs, 17), rs.getInt(18), rs.getBoolean(19));
    }

    private static Instant instant(java.sql.ResultSet rs, int col) throws java.sql.SQLException {
        var t = rs.getObject(col, java.time.OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    // ---- reads ----------------------------------------------------------------------------------------------------

    public Optional<RequestRow> findById(UUID requestId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM gateway.valuation_request WHERE request_id = ?", RequestStore::row, requestId).stream().findFirst();
    }

    /** The result cache lookup: an already COMPLETED request with this key (served by the partial index). */
    public Optional<RequestRow> findCompletedByKey(String requestKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM gateway.valuation_request WHERE request_key = ? AND status = 'COMPLETED'"
                + " ORDER BY completed_at DESC LIMIT 1", RequestStore::row, requestKey).stream().findFirst();
    }

    /** A request with this key that is still PENDING or SENT (so an identical submission can attach to it instead of duplicating work). */
    public Optional<RequestRow> findInFlightByKey(String requestKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM gateway.valuation_request WHERE request_key = ? AND status IN ('PENDING', 'SENT')"
                + " ORDER BY created_at LIMIT 1", RequestStore::row, requestKey).stream().findFirst();
    }

    public int countSent(Lane lane) {
        return jdbc.queryForObject("SELECT count(*) FROM gateway.valuation_request WHERE status = 'SENT' AND lane = ?", Integer.class, lane.name());
    }

    /** The request browser: filter by BRD, status and lane; newest first. */
    public List<RequestRow> browse(LocalDate brd, RequestStatus status, Lane lane, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM gateway.valuation_request WHERE 1 = 1");
        if (brd != null) { sql.append(" AND brd = ?"); args.add(brd); }
        if (status != null) { sql.append(" AND status = ?"); args.add(status.name()); }
        if (lane != null) { sql.append(" AND lane = ?"); args.add(lane.name()); }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), RequestStore::row, args.toArray());
    }

    // ---- writes ---------------------------------------------------------------------------------------------------

    public void insertPending(UUID requestId, String requestKey, String subjectRef, SubjectLevel level, LocalDate brd, String functionalLine,
                              ValuationEngine engine, Lane lane, String source, String payloadJson, boolean provisional) {
        jdbc.update("INSERT INTO gateway.valuation_request (request_id, request_key, subject_ref, subject_level, brd, functional_line, engine,"
                        + " lane, source, status, payload, provisional) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?::jsonb, ?)",
                requestId, requestKey, subjectRef, level.name(), brd, functionalLine, engine.name(), lane.name(), source, payloadJson, provisional);
    }

    /**
     * Locks up to {@code limit} of the oldest PENDING requests of ONE lane for dispatch. Per lane on purpose: a single query over all lanes
     * would fill its LIMIT with a bulk backlog that cannot be dispatched and starve interactive work behind it.
     * SKIP LOCKED lets several dispatchers run without blocking or double-dispatching.
     */
    public List<RequestRow> claimPending(Lane lane, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM gateway.valuation_request WHERE status = 'PENDING' AND lane = ?"
                + " ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED", RequestStore::row, lane.name(), limit);
    }

    /** PENDING -> SENT, counting the attempt. False if someone else already moved it. */
    public boolean markSent(UUID requestId) {
        return jdbc.update("UPDATE gateway.valuation_request SET status = 'SENT', sent_at = now(), attempts = attempts + 1, version = version + 1"
                + " WHERE request_id = ? AND status = 'PENDING'", requestId) == 1;
    }

    /** What a completed request tells the outside world: enough for the result fan-out event. */
    public record Completed(Lane lane, String requestKey, String subjectRef, SubjectLevel level, LocalDate brd, ValuationEngine engine) {}

    /** SENT -> COMPLETED. Returns the request's details if THIS call made the transition; empty for a duplicate or late reply. */
    public Optional<Completed> complete(UUID requestId, String resultJson) {
        return jdbc.query("UPDATE gateway.valuation_request SET status = 'COMPLETED', result = ?::jsonb, error = NULL, completed_at = now(),"
                        + " version = version + 1 WHERE request_id = ? AND status = 'SENT' RETURNING lane, request_key, subject_ref, subject_level, brd, engine",
                (rs, i) -> new Completed(Lane.valueOf(rs.getString(1)), rs.getString(2), rs.getString(3), SubjectLevel.valueOf(rs.getString(4)),
                        rs.getObject(5, LocalDate.class), ValuationEngine.valueOf(rs.getString(6))), resultJson, requestId).stream().findFirst();
    }

    /**
     * SENT -> PENDING (another attempt) while the attempt budget lasts, otherwise SENT -> FAILED with the error recorded.
     * {@code attempts} counts sends, so "maxAttempts = 3" means at most three sends in total.
     */
    public Optional<Transition> failOrRetry(UUID requestId, String error, int maxAttempts) {
        return jdbc.query("UPDATE gateway.valuation_request SET status = CASE WHEN attempts >= ? THEN 'FAILED' ELSE 'PENDING' END, error = ?,"
                        + " completed_at = CASE WHEN attempts >= ? THEN now() ELSE NULL END, version = version + 1"
                        + " WHERE request_id = ? AND status = 'SENT' RETURNING lane, status",
                (rs, i) -> new Transition(Lane.valueOf(rs.getString(1)), RequestStatus.valueOf(rs.getString(2))),
                maxAttempts, error, maxAttempts, requestId).stream().findFirst();
    }

    /** SENT requests of a lane that have been waiting longer than the lane's timeout. */
    public List<UUID> timedOut(Lane lane, Duration timeout) {
        return jdbc.query("SELECT request_id FROM gateway.valuation_request WHERE status = 'SENT' AND lane = ?"
                + " AND sent_at < now() - (? * interval '1 millisecond')", (rs, i) -> rs.getObject(1, UUID.class), lane.name(), timeout.toMillis());
    }
}
