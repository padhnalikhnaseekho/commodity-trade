package io.commodity.logistics.repository;

import io.commodity.logistics.domain.QagRevision;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Access to logistics.qag_revision; queries follow the (quota_ref, brd desc, id desc) index. Ordering is by insertion sequence, never by wall-clock time. */
public interface QagRevisionRepository extends JpaRepository<QagRevision, Long> {
    Optional<QagRevision> findByQagrId(UUID qagrId);

    /** The head of the quota's revision chain (the most recently cut revision). */
    Optional<QagRevision> findFirstByQuotaRefOrderByIdDesc(String quotaRef);

    // "Latest revision for a quota as of a BRD" - the same rule pricing uses: latest BRD, then the insertion sequence (NOT created_at: clocks are not monotonic).
    List<QagRevision> findByQuotaRefAndBrdLessThanEqualOrderByBrdDescIdDesc(String quotaRef, java.time.LocalDate asOf);
}
