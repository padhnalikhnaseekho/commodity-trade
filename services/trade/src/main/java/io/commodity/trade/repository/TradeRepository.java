package io.commodity.trade.repository;

import io.commodity.trade.domain.Trade;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to trade.trade. No custom queries yet; the write path arrives in P0.2. */
public interface TradeRepository extends JpaRepository<Trade, Long> {
    Optional<Trade> findByTradeRef(String tradeRef);
}
