package io.commodity.logistics.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Immutable;
import org.hibernate.type.SqlTypes;

/**
 * One quota-assignment-graph revision (QAGR): a new revision is cut on every MATERIAL change
 * (title/risk transfer, allocation, split, undo-split, merge, blend), never on operational ones.
 *
 * <p>Why it exists: pricing works off immutable revisions rather than live state, which is what
 * makes replay deterministic and P&amp;L restateable. previous_id links the chain so consumers can
 * diff the graph. Talking point: "material vs operational is classified at the producer".
 *
 * <p>WHY @Immutable: revisions are insert-only. Hibernate refuses to issue UPDATEs for this entity.
 */
@Entity
@Immutable
@Table(schema = "logistics", name = "qag_revision")
public class QagRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "qagr_id", nullable = false, unique = true)
    private UUID qagrId;

    @Column(name = "quota_ref", nullable = false)
    private String quotaRef;

    @Column(name = "previous_id")
    private UUID previousId;

    @Column(nullable = false)
    private LocalDate brd;

    // TEXT[] in Postgres. Kept as strings here; the ChangeKind taxonomy is added with the write path in P0.2.
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "change_kinds", nullable = false, columnDefinition = "text[]")
    private String[] changeKinds;

    // Filled by the database default now(); read-only for the application.
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected QagRevision() {} // JPA

    public QagRevision(UUID qagrId, String quotaRef, UUID previousId, LocalDate brd, String[] changeKinds) {
        this.qagrId = qagrId;
        this.quotaRef = quotaRef;
        this.previousId = previousId;
        this.brd = brd;
        this.changeKinds = changeKinds;
    }

    public Long getId() { return id; }
    public UUID getQagrId() { return qagrId; }
    public String getQuotaRef() { return quotaRef; }
    public UUID getPreviousId() { return previousId; }
    public LocalDate getBrd() { return brd; }
    public String[] getChangeKinds() { return changeKinds; }
    public Instant getCreatedAt() { return createdAt; }
}
