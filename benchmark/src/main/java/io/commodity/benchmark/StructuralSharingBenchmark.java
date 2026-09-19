package io.commodity.benchmark;

import io.commodity.contracts.refs.QuotaRef;
import io.commodity.pricing.domain.*;
import io.commodity.pricing.repository.RevisionStore;
import io.commodity.pricing.service.CopyAllRevisionWriter;
import io.commodity.pricing.service.RevisionWriter;
import io.commodity.pricing.service.StructuralSharingRevisionWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The headline demo: write the same pricing revision two ways and report how many rows each wrote.
 *
 * <p>Run it live: {@code ./gradlew :benchmark:run}. The scenario is fixed so the numbers reproduce exactly: a quota of 20 assignments,
 * each with 10 parameters and 3 price components; write it once, then write a new revision that changes 1, then 2, then 5, then 20
 * assignments. Rows are COUNTED (inserts), never timed; wall time is shown only as context.
 *
 * <p>Two independent measurements must agree, or the run fails: the count each writer reports, and the change in the database's own
 * row counts across the five revision tables. It also checks that both strategies resolve to identical content, so a fast wrong
 * answer cannot pass.
 *
 * <p>The last row is the honest one: when EVERYTHING changes, sharing saves nothing (1.0x). Saying so is what makes the other rows
 * believable. The extrapolation printed at the end is labelled as such: it scales the measured ratio, it is not a measurement.
 */
public final class StructuralSharingBenchmark {

    private static final int ASSIGNMENTS = 20, PARAMETERS = 10, COMPONENTS = 3;
    private static final int[] CHANGED = {1, 2, 5, 20};
    private static final LocalDate DAY1 = LocalDate.of(2026, 9, 1), DAY2 = LocalDate.of(2026, 9, 2);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RevisionStore store;

    private StructuralSharingBenchmark(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.store = new RevisionStore(jdbc);
    }

    public static void main(String[] args) {
        try (var postgres = new PostgreSQLContainer<>("postgres:16").withStartupAttempts(5)) {
            postgres.start();
            var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(ds).schemas("pricing").locations("classpath:db/migration/pricing").load().migrate();
            new StructuralSharingBenchmark(new JdbcTemplate(ds), new TransactionTemplate(new DataSourceTransactionManager(ds))).run();
        }
    }

    private record Result(int changed, int copyAllRows, int sharingRows, long copyAllMs, long sharingMs) {
        double ratio() { return (double) copyAllRows / sharingRows; }
    }

    private void run() {
        System.out.printf("Scenario: 1 quota, %d assignments, each with %d parameters and %d price components.%n", ASSIGNMENTS, PARAMETERS, COMPONENTS);
        System.out.println("Write it once, then write a new revision that changes N assignments. Rows are counted, not timed.\n");

        List<Result> results = new ArrayList<>();
        int trade = 9000;
        for (int changed : CHANGED) {
            results.add(measure(changed, ++trade + ".1", ++trade + ".1"));
        }
        print(results);
        extrapolate(results.get(0));
    }

    private Result measure(int changed, String copyQuota, String shareQuota) {
        var copyAll = new CopyAllRevisionWriter(store);
        var sharing = new StructuralSharingRevisionWriter(store);
        var copyBase = content(copyQuota);
        var shareBase = content(shareQuota);
        write(copyAll, copyQuota, DAY1, copyBase);   // first revision: nothing to share yet, both write everything
        write(sharing, shareQuota, DAY1, shareBase);

        long before = totalRows();
        long t0 = System.nanoTime();
        var copyRev = write(copyAll, copyQuota, DAY2, mutate(copyBase, changed));
        long copyMs = (System.nanoTime() - t0) / 1_000_000;
        long mid = totalRows();
        long t1 = System.nanoTime();
        var shareRev = write(sharing, shareQuota, DAY2, mutate(shareBase, changed));
        long shareMs = (System.nanoTime() - t1) / 1_000_000;
        long after = totalRows();

        // Cross-check: the writers' own counts must equal what the database actually gained.
        if (mid - before != copyRev.rowsWritten() || after - mid != shareRev.rowsWritten()) {
            throw new IllegalStateException("row count mismatch: writer says " + copyRev.rowsWritten() + "/" + shareRev.rowsWritten()
                    + ", database says " + (mid - before) + "/" + (after - mid));
        }
        // Cross-check: the optimisation must be invisible to readers.
        var a = store.loadAssignments(copyRev.pqrId());
        var b = store.loadAssignments(shareRev.pqrId());
        if (!strip(a, copyQuota).equals(strip(b, shareQuota))) throw new IllegalStateException("strategies resolved differently");

        return new Result(changed, copyRev.rowsWritten(), shareRev.rowsWritten(), copyMs, shareMs);
    }

    private RevisionWriter.PricingQuotaRevision write(RevisionWriter w, String quota, LocalDate brd, List<AssignmentContent> content) {
        return tx.execute(s -> w.write(QuotaRef.parse(quota), UUID.randomUUID(), brd, content));
    }

    private long totalRows() {
        long n = 0;
        for (String t : List.of("quota_revision", "assignment_revision", "quota_revision_member", "parameter_revision", "price_component")) {
            n += jdbc.queryForObject("SELECT count(*) FROM pricing." + t, Long.class);
        }
        return n;
    }

    private static List<AssignmentContent> content(String quotaRef) {
        List<AssignmentContent> out = new ArrayList<>();
        for (int i = 1; i <= ASSIGNMENTS; i++) {
            List<ParameterContent> ps = new ArrayList<>();
            for (int p = 0; p < PARAMETERS; p++) ps.add(new ParameterContent("ELEMENT_" + p, BigDecimal.valueOf(p + 1)));
            List<PriceComponentContent> cs = new ArrayList<>();
            for (int c = 0; c < COMPONENTS; c++) cs.add(new PriceComponentContent(ComponentKind.FIXED, BigDecimal.valueOf(10), BigDecimal.valueOf(1000 + c), null, null, null, null, false));
            out.add(new AssignmentContent(quotaRef + "." + i, BigDecimal.valueOf(1000), ps, cs));
        }
        return out;
    }

    /** Changes the quantity of the first n assignments: same number of child rows, different content. */
    private static List<AssignmentContent> mutate(List<AssignmentContent> base, int n) {
        List<AssignmentContent> out = new ArrayList<>(base);
        for (int i = 0; i < n; i++) out.set(i, out.get(i).withQty(out.get(i).qty().add(BigDecimal.ONE)));
        return out;
    }

    private static List<AssignmentContent> strip(List<AssignmentContent> content, String quota) {
        return content.stream().map(a -> new AssignmentContent(a.assignmentRef().substring(quota.length()), a.qty(), a.parameters(), a.components())).toList();
    }

    private static void print(List<Result> rs) {
        System.out.println("assignments changed | copy-all rows | sharing rows | ratio | copy-all ms | sharing ms");
        System.out.println("------------------- | ------------- | ------------ | ----- | ----------- | ----------");
        for (Result r : rs) {
            System.out.printf(Locale.ROOT, "%19d | %13d | %12d | %4.1fx | %11d | %10d%n", r.changed(), r.copyAllRows(), r.sharingRows(), r.ratio(), r.copyAllMs(), r.sharingMs());
        }
        System.out.println("\nRow counts are exact: they match the database's own row counts. Wall time is context only (small data, one laptop).");
        System.out.println("The last row is the honest one: when everything changes, sharing saves nothing.");
    }

    private static void extrapolate(Result oneChanged) {
        long revisions = 10_000L * 10;
        double capturedRows = 100_000_000d; // ~10,000 trades x ~10 QAG revisions x ~1,000 inserts (captured figure)
        System.out.println("\nEXTRAPOLATION (scaled from the measurement above; NOT a measurement)");
        System.out.printf(Locale.ROOT, "  ~10,000 trades x ~10 revisions = %,d revisions, one assignment changing in each:%n", revisions);
        System.out.printf(Locale.ROOT, "    modelled here (%d rows/revision):      copy-all ~%,.1fM rows, sharing ~%,.1fM rows%n", oneChanged.copyAllRows(),
                revisions * oneChanged.copyAllRows() / 1e6, revisions * oneChanged.sharingRows() / 1e6);
        System.out.printf(Locale.ROOT, "    captured ~1,000 rows/revision (100M):  sharing ~%,.1fM rows at this measured %.1fx ratio%n", capturedRows / oneChanged.ratio() / 1e6, oneChanged.ratio());
        System.out.println("  The captured 1,000 rows/revision includes rows this demo does not model (blotter row, valuation results).");
    }
}
