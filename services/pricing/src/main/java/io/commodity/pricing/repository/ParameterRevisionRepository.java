package io.commodity.pricing.repository;

import io.commodity.pricing.domain.ParameterRevision;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Insert/read access to the ParameterRevision table. There is deliberately no update or delete path (insert-only). */
public interface ParameterRevisionRepository extends JpaRepository<ParameterRevision, UUID> {}
