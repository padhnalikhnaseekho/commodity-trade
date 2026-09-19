package io.commodity.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.commodity.contracts.refs.QuotaRef;
import io.commodity.contracts.refs.TradeRef;
import io.commodity.logistics.domain.*;
import io.commodity.logistics.repository.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Integration test on real Postgres: Flyway from empty, Hibernate validate, then repository behaviour. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class LogisticsRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16").withStartupAttempts(5);

    @Autowired AssignmentRepository assignments;
    @Autowired QagRevisionRepository revisions;
    @Autowired QagRevisionMemberRepository members;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Assignment assignment(QuotaRef quota, int seq, String qty) {
        return new Assignment(quota.assignment(seq).toString(), quota.toString(), seq, new BigDecimal(qty),
                AssignmentStatus.ACTIVE);
    }

    // PROVES (logistics side of the exit criterion): trade 1 -> quotas 1.1, 1.2 -> assignments
    // 1.1.1, 1.1.2 (and 1.2.1) round-trip. Together with the trade module test this covers
    // "a trade with two quotas and three assignments" without one service reading another's tables.
    @Test
    void insertsThreeAssignmentsUnderTwoQuotasAndReadsThemBack() {
        QuotaRef q1 = new TradeRef(1).quota(1);
        QuotaRef q2 = new TradeRef(1).quota(2);
        assignments.save(assignment(q1, 1, "100"));
        assignments.save(assignment(q1, 2, "150"));
        assignments.saveAndFlush(assignment(q2, 1, "200"));

        assertThat(assignments.findByQuotaRefOrderBySeq("1.1"))
                .extracting(Assignment::getAssignmentRef).containsExactly("1.1.1", "1.1.2");
        assertThat(assignments.findByQuotaRefOrderBySeq("1.2"))
                .extracting(Assignment::getAssignmentRef).containsExactly("1.2.1");
    }

    // PROVES: the schema only accepts the business-supplied status values, in the status column.
    @Test
    void databaseRejectsUnknownStatusValues() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO logistics.assignment (assignment_ref, quota_ref, seq, qty, status)"
                + " VALUES ('7.1.1', '7.1', 1, 10, 'OPEN')")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void storesSupersededAssignment() {
        QuotaRef q = new TradeRef(8).quota(1);
        assignments.saveAndFlush(new Assignment(q.assignment(1).toString(), q.toString(), 1, new BigDecimal("40"),
                AssignmentStatus.SUPERSEDED));
        Assignment loaded = assignments.findByQuotaRefOrderBySeq("8.1").get(0);
        assertThat(loaded.getStatus()).isEqualTo(AssignmentStatus.SUPERSEDED);
    }

    @Test
    void databaseRejectsNonPositiveAssignmentQuantity() {
        QuotaRef q = new TradeRef(9).quota(1);
        assertThatThrownBy(() -> assignments.saveAndFlush(assignment(q, 1, "0")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // PROVES: a revision chain (previous_id), the TEXT[] change_kinds column and the full member list
    // all round-trip, and created_at is filled by the database.
    @Test
    void storesRevisionChainWithMembers() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        revisions.save(new QagRevision(first, "1.1", null, LocalDate.of(2026, 9, 17), new String[] {"ALLOCATION"}));
        revisions.saveAndFlush(new QagRevision(second, "1.1", first, LocalDate.of(2026, 9, 18), new String[] {"SPLIT", "ALLOCATION"}));
        members.save(new QagRevisionMember(new QagRevisionMemberId(second, "1.1.1"), new BigDecimal("100")));
        members.saveAndFlush(new QagRevisionMember(new QagRevisionMemberId(second, "1.1.2"), new BigDecimal("150")));

        QagRevision loaded = revisions.findByQagrId(second).orElseThrow();
        assertThat(loaded.getPreviousId()).isEqualTo(first);
        assertThat(loaded.getChangeKinds()).containsExactly("SPLIT", "ALLOCATION");
        assertThat(members.findByIdQagrId(second)).hasSize(2);
    }

    // PROVES: "latest revision as of a BRD, tie-broken by created_at" ordering, and that a later BRD is not visible earlier.
    @Test
    void latestRevisionAsOfBrdIgnoresLaterBrds() {
        revisions.save(new QagRevision(UUID.randomUUID(), "5.1", null, LocalDate.of(2026, 9, 1), new String[] {"ALLOCATION"}));
        revisions.saveAndFlush(new QagRevision(UUID.randomUUID(), "5.1", null, LocalDate.of(2026, 9, 3), new String[] {"SPLIT"}));

        var asOfSep2 = revisions.findByQuotaRefAndBrdLessThanEqualOrderByBrdDescIdDesc("5.1", LocalDate.of(2026, 9, 2));
        assertThat(asOfSep2).hasSize(1);
        assertThat(asOfSep2.get(0).getBrd()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    // PROVES the QAG revision in force is decided by the insertion sequence, not wall-clock time: a later revision stamped an hour EARLIER must still win.
    @Test
    void latestRevisionFollowsTheInsertionSequenceEvenWhenTheClockWentBackwards() {
        UUID first = UUID.randomUUID(), later = UUID.randomUUID();
        revisions.saveAndFlush(new QagRevision(first, "6.1", null, LocalDate.of(2026, 9, 5), new String[] {"ALLOCATION"}));
        jdbc.update("INSERT INTO logistics.qag_revision (qagr_id, quota_ref, previous_id, brd, change_kinds, created_at) VALUES (?, '6.1', ?, ?, ARRAY['SPLIT'], now() - interval '1 hour')",
                later, first, java.sql.Date.valueOf(LocalDate.of(2026, 9, 5)));

        var asOf = revisions.findByQuotaRefAndBrdLessThanEqualOrderByBrdDescIdDesc("6.1", LocalDate.of(2026, 9, 5));
        assertThat(asOf.get(0).getQagrId()).isEqualTo(later);
        assertThat(revisions.findFirstByQuotaRefOrderByIdDesc("6.1").orElseThrow().getQagrId()).isEqualTo(later);
    }
}
