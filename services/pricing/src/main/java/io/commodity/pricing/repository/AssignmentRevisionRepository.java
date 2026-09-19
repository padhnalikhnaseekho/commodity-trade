package io.commodity.pricing.repository;

import io.commodity.pricing.domain.AssignmentRevision;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Insert/read access to the AssignmentRevision table. There is deliberately no update or delete path (insert-only). */
public interface AssignmentRevisionRepository extends JpaRepository<AssignmentRevision, UUID> {}
