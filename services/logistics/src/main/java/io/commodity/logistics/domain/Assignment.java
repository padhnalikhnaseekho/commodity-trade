package io.commodity.logistics.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * A commercial allocation of quantity inside a quota (logistics.assignment), ref like "1.1.1".
 *
 * <p>Why it exists: assignment is the commercial thing; inventory is the physical thing (kept
 * separate, out of P0 scope). Valuation and close of books work at this grain.
 *
 * <p>INVARIANT (P0.2, domain code): sum(assignment.qty) in a quota never exceeds quota.qty.
 * Only the lifecycle {@link AssignmentStatus} lives here. Approval (for valuation and P&amp;L eligibility) is
 * owned by pricing, and changing it does not cut a QAG revision.
 */
@Entity
@Table(schema = "logistics", name = "assignment")
public class Assignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignment_ref", nullable = false, unique = true)
    private String assignmentRef;

    @Column(name = "quota_ref", nullable = false)
    private String quotaRef;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal qty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssignmentStatus status;

    protected Assignment() {} // JPA

    public Assignment(String assignmentRef, String quotaRef, int seq, BigDecimal qty, AssignmentStatus status) {
        this.assignmentRef = assignmentRef;
        this.quotaRef = quotaRef;
        this.seq = seq;
        this.qty = qty;
        this.status = status;
    }

    public Long getId() { return id; }
    public String getAssignmentRef() { return assignmentRef; }
    public String getQuotaRef() { return quotaRef; }
    public int getSeq() { return seq; }
    public BigDecimal getQty() { return qty; }
    public AssignmentStatus getStatus() { return status; }
}
