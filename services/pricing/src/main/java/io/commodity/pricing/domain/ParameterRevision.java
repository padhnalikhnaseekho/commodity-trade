package io.commodity.pricing.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * A measured physical parameter that adjusts price (pricing.parameter_revision), e.g. silver
 * (premium), arsenic (penalty), coal moisture or particle size. Belongs to one assignment revision.
 *
 * <p>Why it matters for the headline: an assignment can carry ~10 of these, so copying an unchanged
 * assignment copies all of them. That multiplication is the write amplification P0.3 removes.
 * Scale 6 for value; the P0.3 hash uses a canonical decimal form so 10.0 and 10.00 hash alike.
 */
@Entity
@Immutable
@Table(schema = "pricing", name = "parameter_revision")
public class ParameterRevision {

    @Id
    @Column(name = "ppr_id")
    private UUID pprId;

    @Column(name = "par_id", nullable = false)
    private UUID parId;

    @Column(nullable = false)
    private String element;

    @Column(name = "value", nullable = false, precision = 18, scale = 6)
    private BigDecimal value;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    protected ParameterRevision() {} // JPA

    public ParameterRevision(UUID pprId, UUID parId, String element, BigDecimal value, String contentHash) {
        this.pprId = pprId;
        this.parId = parId;
        this.element = element;
        this.value = value;
        this.contentHash = contentHash;
    }

    public UUID getPprId() { return pprId; }
    public UUID getParId() { return parId; }
    public String getElement() { return element; }
    public BigDecimal getValue() { return value; }
    public String getContentHash() { return contentHash; }
}
