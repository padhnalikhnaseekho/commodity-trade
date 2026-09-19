package io.commodity.trade.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The trade contract header (trade.trade). Root of the trade -> quota -> assignment hierarchy.
 *
 * <p>Flow: created by the trade service from a POST /api/trades (P0.2); its quotas are derived
 * from delivery terms; logistics then creates assignments inside each quota.
 *
 * <p>INVARIANT: total_qty > 0 (DB CHECK) and sum(quota.qty) = total_qty (domain code, tested in P0.2).
 * WHY a JPA entity in domain/: the entity is just data; the rules that matter (ref generation,
 * quantity conservation) live in plain classes so they can be unit-tested without Spring.
 */
@Entity
@Table(schema = "trade", name = "trade")
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_ref", nullable = false, unique = true)
    private String tradeRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_line", nullable = false)
    private BusinessLine businessLine;

    @Column(name = "desk_id", nullable = false)
    private String deskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Side side;

    @Column(nullable = false)
    private String counterparty;

    @Column(nullable = false)
    private String commodity;

    // Scale 4 for quantities (project conventions style rule); BigDecimal, never double.
    @Column(name = "total_qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal totalQty;

    @Column(nullable = false)
    private String uom;

    // BRD, not wall-clock: every layer of the platform pins to the desk's business date.
    @Column(name = "created_brd", nullable = false)
    private LocalDate createdBrd;

    protected Trade() {} // JPA

    public Trade(String tradeRef, BusinessLine businessLine, String deskId, Side side, String counterparty,
                 String commodity, BigDecimal totalQty, String uom, LocalDate createdBrd) {
        this.tradeRef = tradeRef;
        this.businessLine = businessLine;
        this.deskId = deskId;
        this.side = side;
        this.counterparty = counterparty;
        this.commodity = commodity;
        this.totalQty = totalQty;
        this.uom = uom;
        this.createdBrd = createdBrd;
    }

    public Long getId() { return id; }
    public String getTradeRef() { return tradeRef; }
    public BusinessLine getBusinessLine() { return businessLine; }
    public String getDeskId() { return deskId; }
    public Side getSide() { return side; }
    public String getCounterparty() { return counterparty; }
    public String getCommodity() { return commodity; }
    public BigDecimal getTotalQty() { return totalQty; }
    public String getUom() { return uom; }
    public LocalDate getCreatedBrd() { return createdBrd; }
}
