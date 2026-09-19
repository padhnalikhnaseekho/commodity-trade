package io.commodity.logistics.repository;

import io.commodity.logistics.domain.Assignment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Access to logistics.assignment. */
public interface AssignmentRepository extends JpaRepository<Assignment, Long> {
    // By seq, not by text ref: text ordering would put 1.1.10 before 1.1.2.
    List<Assignment> findByQuotaRefOrderBySeq(String quotaRef);

    java.util.Optional<Assignment> findByAssignmentRef(String assignmentRef);
}
