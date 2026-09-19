package io.commodity.logistics.repository;

import io.commodity.logistics.domain.QagRevision;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Access to logistics.qag_revision; queries follow the (quota_ref, brd, created_at desc) index. */
public interface QagRevisionRepository extends JpaRepository<QagRevision, Long> {
    Optional<QagRevision> findByQagrId(UUID qagrId);

    // "Latest revision for a quota as of a BRD" - the same rule pricing uses (spec: BRD as-of resolution).
    List<QagRevision> findByQuotaRefAndBrdLessThanEqualOrderByBrdDescCreatedAtDesc(String quotaRef, java.time.LocalDate asOf);
}
