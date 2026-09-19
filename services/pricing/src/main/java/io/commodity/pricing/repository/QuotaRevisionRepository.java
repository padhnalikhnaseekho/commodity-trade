package io.commodity.pricing.repository;

import io.commodity.pricing.domain.QuotaRevision;

import org.springframework.data.jpa.repository.JpaRepository;

/** Insert/read access to the QuotaRevision table. There is deliberately no update or delete path (insert-only). */
public interface QuotaRevisionRepository extends JpaRepository<QuotaRevision, Long> {}
