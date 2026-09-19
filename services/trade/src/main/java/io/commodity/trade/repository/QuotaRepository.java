package io.commodity.trade.repository;

import io.commodity.trade.domain.Quota;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to trade.quota. */
public interface QuotaRepository extends JpaRepository<Quota, Long> {
    // Ordered by seq (numeric) on purpose: ordering by the text quota_ref would put 1.10 before 1.2.
    List<Quota> findByTradeIdOrderBySeq(Long tradeId);
}
