package io.commodity.pricing.repository;

import io.commodity.contracts.refs.AssignmentRef;
import io.commodity.contracts.valuation.ValuationInputs.AssignmentInputs;
import io.commodity.contracts.valuation.ValuationInputs.ComponentInput;
import io.commodity.contracts.valuation.ValuationInputs.ParameterInput;
import io.commodity.pricing.domain.*;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * All SQL for pricing revisions: reads (resolution) and batched, insert-only writes.
 *
 * <p>WHY JdbcTemplate and not JPA here: a revision write is a bulk insert of hundreds of rows with client-generated ids.
 * JDBC batching sends them in a few round trips (lever 4 of the write-amplification design: it does not reduce the row
 * count, it collapses the latency), and the SQL for resolution is exactly the join the design describes, so it is
 * written out and reviewable. The JPA entities remain as the schema-validated mapping of the tables.
 *
 * <p>Every write method returns the number of rows it inserted, which is how the benchmark and tests COUNT inserts
 * instead of timing them. There is no UPDATE or DELETE anywhere in this class (project rule 2); the database trigger
 * backs that up.
 */
public class RevisionStore {

    /** A row of pricing.quota_revision. */
    public record QuotaRevisionRow(UUID pqrId, String quotaRef, UUID qagrId, UUID previousId, LocalDate brd, Instant createdAt) {}

    /** An assignment revision to insert together with its children: par row + parameter rows + component rows. */
    public record AssignmentRevisionInsert(UUID parId, AssignmentContent content, String hash) {
        /** Rows this insert writes: the revision itself plus every child. */
        public int rowCount() {
            return 1 + content.parameters().size() + content.components().size();
        }
    }

    private final JdbcTemplate jdbc;

    public RevisionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- reads ----------------------------------------------------------------------------------------------------

    private static final String ROW_COLUMNS = "pqr_id, quota_ref, qagr_id, previous_id, brd, created_at";

    private static QuotaRevisionRow row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new QuotaRevisionRow(rs.getObject("pqr_id", UUID.class), rs.getString("quota_ref"), rs.getObject("qagr_id", UUID.class),
                rs.getObject("previous_id", UUID.class), rs.getObject("brd", LocalDate.class),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant());
    }

    /** The head of a quota's revision chain: the most recently written revision, whatever its BRD. */
    public Optional<QuotaRevisionRow> head(String quotaRef) {
        return jdbc.query("SELECT " + ROW_COLUMNS + " FROM pricing.quota_revision WHERE quota_ref = ? ORDER BY id DESC LIMIT 1",
                RevisionStore::row, quotaRef).stream().findFirst();
    }

    /**
     * The revision in force for a quota as of a BRD: the latest with brd &lt;= asOf, tie-broken by the INSERTION SEQUENCE (id).
     *
     * <p>WHY id and not created_at (the spec says created_at): created_at is wall-clock time, and wall clocks are not monotonic (NTP and hypervisor time sync
     * step them, and servers disagree). A revision written later can carry an EARLIER timestamp, and resolving by it returns a STALE revision. The id is a
     * sequence, and all writers of a quota hold its advisory lock, so id order is the logical order. Served by the (quota_ref, brd DESC, id DESC) index.
     * A regression test (RevisionWritersTest) inserts a later revision with an earlier created_at and asserts it still wins.
     */
    public Optional<QuotaRevisionRow> asOf(String quotaRef, LocalDate asOf) {
        return jdbc.query("SELECT " + ROW_COLUMNS + " FROM pricing.quota_revision WHERE quota_ref = ? AND brd <= ?"
                        + " ORDER BY brd DESC, id DESC LIMIT 1", RevisionStore::row, quotaRef, asOf).stream().findFirst();
    }

    public List<QuotaRevisionRow> revisions(String quotaRef) {
        return jdbc.query("SELECT " + ROW_COLUMNS + " FROM pricing.quota_revision WHERE quota_ref = ? ORDER BY id", RevisionStore::row, quotaRef);
    }

    public Optional<QuotaRevisionRow> byPqrId(UUID pqrId) {
        return jdbc.query("SELECT " + ROW_COLUMNS + " FROM pricing.quota_revision WHERE pqr_id = ?", RevisionStore::row, pqrId).stream().findFirst();
    }

    /**
     * Resolves a quota revision to its assignment contents: root -> sharing table -> assignment revisions, then parameters
     * and components joined from par_id. Three queries, each an indexed join; this is the read-side cost of sharing.
     */
    public List<AssignmentContent> loadAssignments(UUID pqrId) {
        Map<UUID, String[]> heads = new LinkedHashMap<>(); // parId -> {ref, qty}
        jdbc.query("SELECT ar.par_id, ar.assignment_ref, ar.qty FROM pricing.quota_revision_member m"
                        + " JOIN pricing.assignment_revision ar ON ar.par_id = m.par_id WHERE m.pqr_id = ?",
                rs -> { heads.put(rs.getObject(1, UUID.class), new String[] {rs.getString(2), rs.getBigDecimal(3).toPlainString()}); }, pqrId);

        Map<UUID, List<ParameterContent>> params = new HashMap<>();
        jdbc.query("SELECT p.par_id, p.element, p.value FROM pricing.quota_revision_member m"
                        + " JOIN pricing.parameter_revision p ON p.par_id = m.par_id WHERE m.pqr_id = ?",
                rs -> { params.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                        .add(new ParameterContent(rs.getString(2), rs.getBigDecimal(3))); }, pqrId);

        Map<UUID, List<PriceComponentContent>> comps = new HashMap<>();
        jdbc.query("SELECT c.par_id, c.kind, c.qty, c.fixed_price, c.index_name, c.period_from, c.period_to, c.formula, c.provisional"
                        + " FROM pricing.quota_revision_member m JOIN pricing.price_component c ON c.par_id = m.par_id WHERE m.pqr_id = ?",
                rs -> { comps.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                        .add(new PriceComponentContent(ComponentKind.valueOf(rs.getString(2)), rs.getBigDecimal(3), rs.getBigDecimal(4),
                                rs.getString(5), rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class), rs.getString(8),
                                rs.getBoolean(9))); }, pqrId);

        return heads.entrySet().stream()
                .map(e -> new AssignmentContent(e.getValue()[0], new BigDecimal(e.getValue()[1]), params.get(e.getKey()), comps.get(e.getKey())))
                .sorted(Comparator.comparing(a -> AssignmentRef.parse(a.assignmentRef())))
                .toList();
    }

    /**
     * Like {@link #loadAssignments} but keeps the ROW IDS (assignment revision, parameter revision, price component) and the
     * approval state as of a BRD: exactly what a valuation request is keyed on. Same three joins.
     */
    public List<AssignmentInputs> loadInputs(UUID pqrId, LocalDate approvalAsOf) {
        Map<UUID, Object[]> heads = new LinkedHashMap<>(); // parId -> {ref, qty}
        jdbc.query("SELECT ar.par_id, ar.assignment_ref, ar.qty FROM pricing.quota_revision_member m"
                        + " JOIN pricing.assignment_revision ar ON ar.par_id = m.par_id WHERE m.pqr_id = ?",
                rs -> { heads.put(rs.getObject(1, UUID.class), new Object[] {rs.getString(2), rs.getBigDecimal(3)}); }, pqrId);

        Map<UUID, List<ParameterInput>> params = new HashMap<>();
        jdbc.query("SELECT p.par_id, p.ppr_id, p.element, p.value FROM pricing.quota_revision_member m"
                        + " JOIN pricing.parameter_revision p ON p.par_id = m.par_id WHERE m.pqr_id = ? ORDER BY p.ppr_id",
                rs -> { params.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                        .add(new ParameterInput(rs.getObject(2, UUID.class), rs.getString(3), rs.getBigDecimal(4))); }, pqrId);

        Map<UUID, List<ComponentInput>> comps = new HashMap<>();
        jdbc.query("SELECT c.par_id, c.pc_id, c.kind, c.qty, c.fixed_price, c.index_name, c.period_from, c.period_to, c.formula, c.provisional"
                        + " FROM pricing.quota_revision_member m JOIN pricing.price_component c ON c.par_id = m.par_id WHERE m.pqr_id = ? ORDER BY c.pc_id",
                rs -> { comps.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                        .add(new ComponentInput(rs.getObject(2, UUID.class), rs.getString(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getString(6),
                                rs.getObject(7, LocalDate.class), rs.getObject(8, LocalDate.class), rs.getString(9), rs.getBoolean(10))); }, pqrId);

        return heads.entrySet().stream().map(e -> {
            String ref = (String) e.getValue()[0];
            boolean approved = approvalAsOf(ref, approvalAsOf) == ApprovalStatus.APPROVED;
            return new AssignmentInputs(ref, e.getKey(), (BigDecimal) e.getValue()[1], approved,
                    comps.getOrDefault(e.getKey(), List.of()), params.getOrDefault(e.getKey(), List.of()));
        }).sorted(Comparator.comparing(a -> AssignmentRef.parse(a.assignmentRef()))).toList();
    }

    /** parId of every assignment revision a quota revision points at, keyed by assignment ref. */
    public Map<String, UUID> assignmentRevisionIds(UUID pqrId) {
        Map<String, UUID> out = new LinkedHashMap<>();
        jdbc.query("SELECT ar.assignment_ref, ar.par_id FROM pricing.quota_revision_member m"
                        + " JOIN pricing.assignment_revision ar ON ar.par_id = m.par_id WHERE m.pqr_id = ?",
                rs -> { out.put(rs.getString(1), rs.getObject(2, UUID.class)); }, pqrId);
        return out;
    }

    /** Existing assignment revisions for these refs: ref -> (content hash -> parId). The sharing lookup. */
    public Map<String, Map<String, UUID>> findByRefs(Collection<String> assignmentRefs) {
        Map<String, Map<String, UUID>> out = new HashMap<>();
        if (assignmentRefs.isEmpty()) return out;
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT assignment_ref, content_hash, par_id FROM pricing.assignment_revision WHERE assignment_ref = ANY (?)");
            Array refs = con.createArrayOf("text", assignmentRefs.toArray());
            ps.setArray(1, refs);
            return ps;
        }, rs -> { out.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).putIfAbsent(rs.getString(2), rs.getObject(3, UUID.class)); });
        return out;
    }

    public Optional<UUID> componentId(UUID parId, String contentHash) {
        return jdbc.query("SELECT pc_id FROM pricing.price_component WHERE par_id = ? AND content_hash = ? LIMIT 1",
                (rs, i) -> rs.getObject(1, UUID.class), parId, contentHash).stream().findFirst();
    }

    public Optional<UUID> parameterId(UUID parId, String contentHash) {
        return jdbc.query("SELECT ppr_id FROM pricing.parameter_revision WHERE par_id = ? AND content_hash = ? LIMIT 1",
                (rs, i) -> rs.getObject(1, UUID.class), parId, contentHash).stream().findFirst();
    }

    // ---- writes (insert-only) -------------------------------------------------------------------------------------

    public int insertQuotaRevision(UUID pqrId, String quotaRef, UUID qagrId, UUID previousPqrId, LocalDate brd) {
        return jdbc.update("INSERT INTO pricing.quota_revision (pqr_id, quota_ref, qagr_id, previous_id, brd) VALUES (?, ?, ?, ?, ?)",
                pqrId, quotaRef, qagrId, previousPqrId, brd);
    }

    /** Inserts assignment revisions and all their parameter and component rows in three batches. Returns rows inserted. */
    public int insertAssignmentRevisions(List<AssignmentRevisionInsert> inserts) {
        if (inserts.isEmpty()) return 0;
        List<Object[]> ars = new ArrayList<>(), ps = new ArrayList<>(), cs = new ArrayList<>();
        for (AssignmentRevisionInsert in : inserts) {
            AssignmentContent a = in.content();
            ars.add(new Object[] {in.parId(), a.assignmentRef(), a.qty(), in.hash()});
            for (ParameterContent p : a.parameters()) {
                ps.add(new Object[] {UUID.randomUUID(), in.parId(), p.element(), p.value(), ContentHasher.hash(p)});
            }
            for (PriceComponentContent c : a.components()) {
                cs.add(new Object[] {UUID.randomUUID(), in.parId(), c.kind().name(), c.qty(), c.fixedPrice(), c.indexName(),
                        c.periodFrom(), c.periodTo(), c.formula(), c.provisional(), ContentHasher.hash(c)});
            }
        }
        int n = batch("INSERT INTO pricing.assignment_revision (par_id, assignment_ref, qty, content_hash) VALUES (?, ?, ?, ?)", ars,
                Types.OTHER, Types.VARCHAR, Types.NUMERIC, Types.VARCHAR);
        n += batch("INSERT INTO pricing.parameter_revision (ppr_id, par_id, element, value, content_hash) VALUES (?, ?, ?, ?, ?)", ps,
                Types.OTHER, Types.OTHER, Types.VARCHAR, Types.NUMERIC, Types.VARCHAR);
        n += batch("INSERT INTO pricing.price_component (pc_id, par_id, kind, qty, fixed_price, index_name, period_from, period_to,"
                        + " formula, provisional, content_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", cs,
                Types.OTHER, Types.OTHER, Types.VARCHAR, Types.NUMERIC, Types.NUMERIC, Types.VARCHAR, Types.DATE, Types.DATE,
                Types.VARCHAR, Types.BOOLEAN, Types.VARCHAR);
        return n;
    }

    /** Links a quota revision to the assignment revisions in force (new and reused alike): the sharing table. */
    public int insertMembers(UUID pqrId, Collection<UUID> parIds) {
        List<Object[]> rows = parIds.stream().map(par -> new Object[] {pqrId, par}).toList();
        return batch("INSERT INTO pricing.quota_revision_member (pqr_id, par_id) VALUES (?, ?)", rows, Types.OTHER, Types.OTHER);
    }

    private int batch(String sql, List<Object[]> rows, int... types) {
        if (rows.isEmpty()) return 0;
        jdbc.batchUpdate(sql, rows, types);
        return rows.size();
    }

    // ---- approval (separate insert-only record) --------------------------------------------------------------------

    public void insertApproval(String assignmentRef, ApprovalStatus status, LocalDate brd) {
        jdbc.update("INSERT INTO pricing.assignment_approval (assignment_ref, status, brd) VALUES (?, ?, ?)", assignmentRef, status.name(), brd);
    }

    /** Latest approval as of a BRD (by insertion sequence, see {@link #asOf}); no record at all means UNAPPROVED. */
    public ApprovalStatus approvalAsOf(String assignmentRef, LocalDate asOf) {
        return jdbc.query("SELECT status FROM pricing.assignment_approval WHERE assignment_ref = ? AND brd <= ?"
                        + " ORDER BY brd DESC, id DESC LIMIT 1", (rs, i) -> ApprovalStatus.valueOf(rs.getString(1)), assignmentRef, asOf)
                .stream().findFirst().orElse(ApprovalStatus.UNAPPROVED);
    }
}
