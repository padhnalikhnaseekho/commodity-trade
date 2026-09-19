package io.commodity.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.commodity.contracts.refs.TradeRef;
import io.commodity.trade.domain.*;
import io.commodity.trade.repository.QuotaRepository;
import io.commodity.trade.repository.TradeRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test against a real Postgres (Testcontainers): Flyway applies the migration from empty,
 * Hibernate validates entities against it, then we exercise the repositories.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // use the container, not H2
@Testcontainers
class TradeRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired TradeRepository trades;
    @Autowired QuotaRepository quotas;

    private Trade newTrade(TradeRef ref, String qty) {
        return new Trade(ref.toString(), BusinessLine.CONCENTRATES, "DESK-1", Side.PURCHASE,
                "ACME Mining", "Copper concentrate", new BigDecimal(qty), "MT", LocalDate.of(2026, 9, 18));
    }

    // PROVES (trade side of the exit criterion): trade 1 with quotas 1.1 and 1.2 round-trips.
    // The assignments 1.1.1 / 1.1.2 belong to logistics and are tested in that module.
    @Test
    void insertsTradeWithTwoQuotasAndReadsThemBack() {
        TradeRef ref = new TradeRef(1);
        Trade trade = trades.saveAndFlush(newTrade(ref, "1000.0000"));
        quotas.save(new Quota(ref.quota(1).toString(), trade.getId(), 1, Periodicity.MONTHLY,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), new BigDecimal("500.0000")));
        quotas.saveAndFlush(new Quota(ref.quota(2).toString(), trade.getId(), 2, Periodicity.MONTHLY,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), new BigDecimal("500.0000")));

        assertThat(trades.findByTradeRef("1")).isPresent();
        assertThat(quotas.findByTradeIdOrderBySeq(trade.getId()))
                .extracting(Quota::getQuotaRef).containsExactly("1.1", "1.2");
    }

    // PROVES: constraints live in the schema, not only in code. A zero-quantity trade is rejected by the DB.
    @Test
    void databaseRejectsNonPositiveTradeQuantity() {
        assertThatThrownBy(() -> trades.saveAndFlush(newTrade(new TradeRef(2), "0")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // PROVES: (trade_id, seq) is unique, so two quota "1.1"s cannot coexist even under a race.
    @Test
    void databaseRejectsDuplicateQuotaSequence() {
        Trade trade = trades.saveAndFlush(newTrade(new TradeRef(3), "100"));
        quotas.saveAndFlush(new Quota("3.1", trade.getId(), 1, Periodicity.MONTHLY,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), new BigDecimal("50")));
        assertThatThrownBy(() -> quotas.saveAndFlush(new Quota("3.9", trade.getId(), 1, Periodicity.MONTHLY,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), new BigDecimal("50"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
