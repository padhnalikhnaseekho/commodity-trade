package io.commodity.pricing.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * An immutable snapshot of one assignment's pricing content (pricing.assignment_revision).
 *
 * <p>Why it exists: this is the shared node. When an assignment does not change between quota
 * revisions, the new quota revision reuses this row instead of copying it and all its children.
 * contentHash (SHA-256 over own fields plus children's hashes, computed in P0.3) is the key: equal
 * hash means equal content, so reuse is safe.
 *
 * <p>Talking point: same trick as Git trees; rows are immutable so pointing at them is safe.
 * The primary key is a client-generated UUID so the writer knows the id without a round trip.
 */
@Entity
@Immutable
@Table(schema = "pricing", name = "assignment_revision")
public class AssignmentRevision {

    @Id
    @Column(name = "par_id")
    private UUID parId;

    @Column(name = "assignment_ref", nullable = false)
    private String assignmentRef;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal qty;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    protected AssignmentRevision() {} // JPA

    public AssignmentRevision(UUID parId, String assignmentRef, BigDecimal qty, String contentHash) {
        this.parId = parId;
        this.assignmentRef = assignmentRef;
        this.qty = qty;
        this.contentHash = contentHash;
    }

    public UUID getParId() { return parId; }
    public String getAssignmentRef() { return assignmentRef; }
    public BigDecimal getQty() { return qty; }
    public String getContentHash() { return contentHash; }
}
