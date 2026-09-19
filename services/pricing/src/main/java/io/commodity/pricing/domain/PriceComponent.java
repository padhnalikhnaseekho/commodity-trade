package io.commodity.pricing.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * A quantity-scoped price attached to an assignment revision (pricing.price_component): FIXED (a direct
 * value), AVERAGE (index over a period) or FORMULA (expression over indices).
 *
 * <p>Why quantity-scoped: price fixation is incremental. Fix 10 MT of a 100 MT assignment and the other 90 MT
 * stays unpriced. Unpriced quantity is therefore DERIVED (assignment qty - sum of component qty) and never
 * stored (project conventions rule 3).
 *
 * <p>INVARIANT: sum(component qty) &lt;= assignment qty (domain code, P0.3; the API returns 409 with the numbers).
 * Optional columns depend on kind; which combinations are valid is validated in domain code, not the schema.
 */
@Entity
@Immutable
@Table(schema = "pricing", name = "price_component")
public class PriceComponent {

    @Id
    @Column(name = "pc_id")
    private UUID pcId;

    @Column(name = "par_id", nullable = false)
    private UUID parId;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal qty;

    @Column(name = "fixed_price", precision = 18, scale = 6)
    private BigDecimal fixedPrice;

    @Column(name = "index_name")
    private String indexName;

    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

    @Column
    private String formula;

    @Column(nullable = false)
    private boolean provisional;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    protected PriceComponent() {} // JPA

    public PriceComponent(UUID pcId, UUID parId, String kind, BigDecimal qty, BigDecimal fixedPrice, String indexName,
                          LocalDate periodFrom, LocalDate periodTo, String formula, boolean provisional, String contentHash) {
        this.pcId = pcId;
        this.parId = parId;
        this.kind = kind;
        this.qty = qty;
        this.fixedPrice = fixedPrice;
        this.indexName = indexName;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.formula = formula;
        this.provisional = provisional;
        this.contentHash = contentHash;
    }

    public UUID getPcId() { return pcId; }
    public UUID getParId() { return parId; }
    public String getKind() { return kind; }
    public BigDecimal getQty() { return qty; }
    public BigDecimal getFixedPrice() { return fixedPrice; }
    public String getIndexName() { return indexName; }
    public LocalDate getPeriodFrom() { return periodFrom; }
    public LocalDate getPeriodTo() { return periodTo; }
    public String getFormula() { return formula; }
    public boolean isProvisional() { return provisional; }
    public String getContentHash() { return contentHash; }
}
