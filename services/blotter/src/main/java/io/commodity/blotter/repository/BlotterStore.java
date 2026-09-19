package io.commodity.blotter.repository;

import io.commodity.blotter.domain.RowView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * All SQL of the blotter read model: idempotent upserts from events, and the as-of read.
 *
 * <p>Upserts are IDEMPOTENT by construction (INSERT ... ON CONFLICT DO UPDATE on the natural key), so an event delivered twice, or replayed from the start
 * of the topic to rebuild the read model, gives the same table. That is why the blotter needs no dedup table.
 */
public class BlotterStore {

    /** The stored form of a row (what a pricing snapshot says about one assignment on one BRD). */
    public record RowState(String assignmentRef, LocalDate brd, String tradeRef, String quotaRef, String deskId, String businessLine, String commodity,
                           BigDecimal qty, BigDecimal pricedQty, BigDecimal unpricedQty, boolean overFixed, String approvalStatus, String status, UUID pqrId) {}

    /** A date later than any real business date: "the live view", as opposed to a past as-of date. (Postgres dates cannot hold LocalDate.MAX.) */
    public static final LocalDate LIVE = LocalDate.of(9999, 12, 31);

    private final JdbcTemplate jdbc;

    public BlotterStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertRow(RowState r) {
        jdbc.update("INSERT INTO blotter.blotter_row (assignment_ref, brd, trade_ref, quota_ref, desk_id, business_line, commodity, qty, priced_qty, unpriced_qty,"
                        + " over_fixed, approval_status, status, pqr_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (assignment_ref, brd) DO UPDATE SET trade_ref = EXCLUDED.trade_ref, quota_ref = EXCLUDED.quota_ref, desk_id = EXCLUDED.desk_id,"
                        + " business_line = EXCLUDED.business_line, commodity = EXCLUDED.commodity, qty = EXCLUDED.qty, priced_qty = EXCLUDED.priced_qty,"
                        + " unpriced_qty = EXCLUDED.unpriced_qty, over_fixed = EXCLUDED.over_fixed, approval_status = EXCLUDED.approval_status,"
                        + " status = EXCLUDED.status, pqr_id = EXCLUDED.pqr_id, updated_at = now()",
                r.assignmentRef(), r.brd(), r.tradeRef(), r.quotaRef(), r.deskId(), r.businessLine(), r.commodity(), r.qty(), r.pricedQty(), r.unpricedQty(),
                r.overFixed(), r.approvalStatus(), r.status(), r.pqrId());
    }

    public void upsertValuation(String subjectRef, String level, LocalDate brd, BigDecimal value, String engine, UUID requestId, Instant completedAt) {
        jdbc.update("INSERT INTO blotter.valuation (subject_ref, level, brd, value, engine, request_id, completed_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (subject_ref, level, brd) DO UPDATE SET value = EXCLUDED.value, engine = EXCLUDED.engine, request_id = EXCLUDED.request_id,"
                        + " completed_at = EXCLUDED.completed_at",
                subjectRef, level, brd, value, engine, requestId, java.sql.Timestamp.from(completedAt));
    }

    /**
     * The rows in force as of a BRD (latest row per assignment with brd &lt;= asOf, ACTIVE only), each with its latest valuation as of the same date.
     * Optionally filtered by desk and/or quota. Assignment-level valuation wins over a quota-level one. The provisional flag is NOT set here (see Provisional).
     */
    public List<RowView> rows(String deskId, String quotaRef, LocalDate asOf) {
        return jdbc.query("""
                SELECT r.assignment_ref, r.trade_ref, r.quota_ref, r.desk_id, r.business_line, r.commodity, r.brd, r.qty, r.priced_qty, r.unpriced_qty,
                       r.over_fixed, r.approval_status, r.status,
                       va.value AS va_value, va.brd AS va_brd, va.engine AS va_engine,
                       vq.value AS vq_value, vq.brd AS vq_brd, vq.engine AS vq_engine
                FROM (SELECT DISTINCT ON (assignment_ref) * FROM blotter.blotter_row
                       WHERE brd <= ? AND (?::text IS NULL OR desk_id = ?) AND (?::text IS NULL OR quota_ref = ?)
                       ORDER BY assignment_ref, brd DESC) r
                LEFT JOIN LATERAL (SELECT value, brd, engine FROM blotter.valuation v
                                    WHERE v.subject_ref = r.assignment_ref AND v.level = 'ASSIGNMENT' AND v.brd <= ? ORDER BY v.brd DESC LIMIT 1) va ON true
                LEFT JOIN LATERAL (SELECT value, brd, engine FROM blotter.valuation v
                                    WHERE v.subject_ref = r.quota_ref AND v.level = 'QUOTA' AND v.brd <= ? ORDER BY v.brd DESC LIMIT 1) vq ON true
                WHERE r.status = 'ACTIVE'
                """,
                (rs, i) -> {
                    boolean assignmentLevel = rs.getBigDecimal("va_value") != null;
                    boolean quotaLevel = !assignmentLevel && rs.getBigDecimal("vq_value") != null;
                    return new RowView(rs.getString("assignment_ref"), rs.getString("trade_ref"), rs.getString("quota_ref"), rs.getString("desk_id"),
                            rs.getString("business_line"), rs.getString("commodity"), rs.getObject("brd", LocalDate.class),
                            rs.getBigDecimal("qty").toPlainString(), rs.getBigDecimal("priced_qty").toPlainString(), rs.getBigDecimal("unpriced_qty").toPlainString(),
                            rs.getBoolean("over_fixed"), rs.getString("approval_status"), rs.getString("status"),
                            assignmentLevel ? rs.getBigDecimal("va_value").toPlainString() : quotaLevel ? rs.getBigDecimal("vq_value").toPlainString() : null,
                            assignmentLevel ? rs.getObject("va_brd", LocalDate.class) : quotaLevel ? rs.getObject("vq_brd", LocalDate.class) : null,
                            assignmentLevel ? "ASSIGNMENT" : quotaLevel ? "QUOTA" : null,
                            assignmentLevel ? rs.getString("va_engine") : quotaLevel ? rs.getString("vq_engine") : null, null);
                },
                asOf, deskId, deskId, quotaRef, quotaRef, asOf, asOf);
    }
}
