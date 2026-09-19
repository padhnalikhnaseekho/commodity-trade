package io.commodity.pricing.repository;

import io.commodity.pricing.domain.PriceComponent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Insert/read access to the PriceComponent table. There is deliberately no update or delete path (insert-only). */
public interface PriceComponentRepository extends JpaRepository<PriceComponent, UUID> {}
