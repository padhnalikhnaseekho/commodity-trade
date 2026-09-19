package io.commodity.pricing.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * Root of a pricing snapshot for a quota (pricing.quota_revision): one row per pricing change.
 *
 * <p>Why it exists: it is the unit that valuation requests and replay pin to. It does not own its
 * assignment revisions; it POINTS at them through quota_revision_member, which is what makes
 * structural sharing possible (P0.3).
 *
 * <p>INVARIANT: insert-only. @Immutable stops Hibernate updating it; a database trigger stops everyone else
 * (proved by PricingRepositoryTest). qagrId is a cross-service reference by id only, never a foreign key.
 */
@Entity
@Immutable
@Table(schema = "pricing", name = "quota_revision")
public class QuotaRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pqr_id", nullable = false, unique = true)
    private UUID pqrId;

    @Column(name = "quota_ref", nullable = false)
    private String quotaRef;

    @Column(name = "qagr_id", nullable = false)
    private UUID qagrId;

    @Column(name = "previous_id")
    private UUID previousId;

    @Column(nullable = false)
    private LocalDate brd;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected QuotaRevision() {} // JPA

    public QuotaRevision(UUID pqrId, String quotaRef, UUID qagrId, UUID previousId, LocalDate brd) {
        this.pqrId = pqrId;
        this.quotaRef = quotaRef;
        this.qagrId = qagrId;
        this.previousId = previousId;
        this.brd = brd;
    }

    public Long getId() { return id; }
    public UUID getPqrId() { return pqrId; }
    public String getQuotaRef() { return quotaRef; }
    public UUID getQagrId() { return qagrId; }
    public UUID getPreviousId() { return previousId; }
    public LocalDate getBrd() { return brd; }
    public Instant getCreatedAt() { return createdAt; }
}
