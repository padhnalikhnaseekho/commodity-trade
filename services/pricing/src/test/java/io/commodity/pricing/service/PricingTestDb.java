package io.commodity.pricing.service;

import io.commodity.pricing.repository.RevisionStore;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One shared Postgres for the non-Spring pricing tests (the "singleton container" pattern): started once per JVM,
 * migrated with the real Flyway migrations, and reused by both the JUnit tests and the jqwik property (jqwik has no
 * Testcontainers integration of its own). Each test uses its own quota refs, so tests never see each other's rows.
 */
final class PricingTestDb {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    private static final AtomicInteger NEXT_TRADE = new AtomicInteger(1000);
    static final JdbcTemplate JDBC;
    static final TransactionTemplate TX;

    static {
        POSTGRES.start();
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&reWriteBatchedInserts=true".replace("&", POSTGRES.getJdbcUrl().contains("?") ? "&" : "?"),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).schemas("pricing").locations("classpath:db/migration/pricing").load().migrate();
        JDBC = new JdbcTemplate(ds);
        TX = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    private PricingTestDb() {}

    static RevisionStore store() {
        return new RevisionStore(JDBC);
    }

    /** A quota ref no other test uses, e.g. "1007.1". */
    static String freshQuotaRef() {
        return NEXT_TRADE.incrementAndGet() + ".1";
    }

    /** Runs one revision write in its own transaction, as the service layer would. */
    static RevisionWriter.PricingQuotaRevision write(RevisionWriter writer, String quotaRef, LocalDate brd,
                                                     java.util.List<io.commodity.pricing.domain.AssignmentContent> content) {
        return TX.execute(s -> writer.write(io.commodity.contracts.refs.QuotaRef.parse(quotaRef), UUID.randomUUID(), brd, content));
    }

    static long count(String table) {
        return JDBC.queryForObject("SELECT count(*) FROM pricing." + table, Long.class);
    }

    /** Total rows across the five revision tables, the independent measure the benchmark checks writers against. */
    static long revisionRows() {
        return count("quota_revision") + count("assignment_revision") + count("quota_revision_member")
                + count("parameter_revision") + count("price_component");
    }
}
