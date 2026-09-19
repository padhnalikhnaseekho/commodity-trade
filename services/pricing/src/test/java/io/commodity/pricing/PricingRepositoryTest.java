package io.commodity.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.commodity.pricing.domain.*;
import io.commodity.pricing.repository.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Integration test on real Postgres: schema from Flyway, entity mapping validated, immutability enforced. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PricingRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16").withStartupAttempts(5);

    @Autowired QuotaRevisionRepository quotaRevisions;
    @Autowired AssignmentRevisionRepository assignmentRevisions;
    @Autowired QuotaRevisionMemberRepository members;
    @Autowired ParameterRevisionRepository parameters;
    @Autowired PriceComponentRepository components;
    @Autowired JdbcTemplate jdbc;

    /** Writes one quota revision pointing at one assignment revision that has a parameter and a component. */
    private UUID[] insertSmallTree() {
        UUID pqr = UUID.randomUUID(), par = UUID.randomUUID();
        quotaRevisions.save(new QuotaRevision(pqr, "1.1", UUID.randomUUID(), null, LocalDate.of(2026, 9, 18)));
        assignmentRevisions.save(new AssignmentRevision(par, "1.1.1", new BigDecimal("100"), "hash-a"));
        members.save(new QuotaRevisionMember(new QuotaRevisionMemberId(pqr, par)));
        parameters.save(new ParameterRevision(UUID.randomUUID(), par, "AG", new BigDecimal("12.500000"), "hash-p"));
        components.saveAndFlush(new PriceComponent(UUID.randomUUID(), par, "FIXED", new BigDecimal("10"),
                new BigDecimal("8500.000000"), null, null, null, null, false, "hash-c"));
        return new UUID[] {pqr, par};
    }

    // PROVES: the whole revision tree (root -> sharing table -> assignment -> parameters/components) round-trips.
    @Test
    void storesAndReadsBackARevisionTree() {
        UUID[] ids = insertSmallTree();

        assertThat(quotaRevisions.findAll()).extracting(QuotaRevision::getPqrId).contains(ids[0]);
        assertThat(members.findById(new QuotaRevisionMemberId(ids[0], ids[1]))).isPresent();
        assertThat(parameters.findAll()).extracting(ParameterRevision::getElement).contains("AG");
        assertThat(components.findAll()).extracting(PriceComponent::getKind).contains("FIXED");
    }

    // PROVES the interview claim "revisions are immutable": even raw SQL cannot UPDATE or DELETE them.
    // Hibernate @Immutable guards our own code; this trigger guards everything else.
    // NOT_SUPPORTED: Postgres aborts a transaction after the first error, so the four rejected statements
    // must each run outside the test's wrapping transaction. Rows written here are committed; other tests
    // use fresh UUIDs and never assume an empty table, so this is safe.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void databaseRejectsUpdateAndDeleteOnRevisionTables() {
        UUID[] ids = insertSmallTree();

        assertThatThrownBy(() -> jdbc.update("UPDATE pricing.assignment_revision SET qty = 1 WHERE par_id = ?", ids[1]))
                .hasMessageContaining("insert-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM pricing.price_component WHERE par_id = ?", ids[1]))
                .hasMessageContaining("insert-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE pricing.quota_revision SET brd = brd + 1 WHERE pqr_id = ?", ids[0]))
                .hasMessageContaining("insert-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM pricing.quota_revision_member WHERE pqr_id = ?", ids[0]))
                .hasMessageContaining("insert-only");
    }

    // PROVES: the price_component quantity check lives in the schema too (qty must be positive).
    @Test
    void databaseRejectsNonPositiveComponentQuantity() {
        UUID par = UUID.randomUUID();
        assignmentRevisions.saveAndFlush(new AssignmentRevision(par, "2.1.1", new BigDecimal("50"), "h"));
        assertThatThrownBy(() -> components.saveAndFlush(new PriceComponent(UUID.randomUUID(), par, "FIXED",
                BigDecimal.ZERO, new BigDecimal("1"), null, null, null, null, false, "h")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
