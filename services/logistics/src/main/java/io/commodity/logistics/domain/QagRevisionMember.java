package io.commodity.logistics.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import org.hibernate.annotations.Immutable;

/**
 * An assignment as it stood inside one QAG revision (full membership, not a delta).
 *
 * <p>WHY full membership: the QAGR event carries the diff for consumers that only need the change,
 * but also the complete member list so a consumer can rebuild state without reading back. The
 * trade-off is a few extra rows per revision in exchange for consumers that never call logistics.
 */
@Entity
@Immutable
@Table(schema = "logistics", name = "qag_revision_member")
public class QagRevisionMember {

    @EmbeddedId
    private QagRevisionMemberId id;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal qty;

    protected QagRevisionMember() {} // JPA

    public QagRevisionMember(QagRevisionMemberId id, BigDecimal qty) {
        this.id = id;
        this.qty = qty;
    }

    public QagRevisionMemberId getId() { return id; }
    public BigDecimal getQty() { return qty; }
}
