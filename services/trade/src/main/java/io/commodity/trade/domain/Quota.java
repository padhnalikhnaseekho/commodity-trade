package io.commodity.trade.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One delivery period of a trade (trade.quota), e.g. January's share of a monthly schedule.
 *
 * <p>Why it exists: the quota is the aggregate that logistics assignments, QAG revisions and
 * pricing revisions all hang off. RM even values and prices at quota level.
 *
 * <p>WHY trade_id is a plain Long and not a @ManyToOne: keeps the entity a flat row, avoids lazy
 * loading surprises, and mirrors how other services see it (by ref only).
 */
@Entity
@Table(schema = "trade", name = "quota")
public class Quota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "quota_ref", nullable = false, unique = true)
    private String quotaRef;

    @Column(name = "trade_id", nullable = false)
    private Long tradeId;

    @Column(nullable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Periodicity periodicity;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal qty;

    protected Quota() {} // JPA

    public Quota(String quotaRef, Long tradeId, int seq, Periodicity periodicity,
                 LocalDate periodFrom, LocalDate periodTo, BigDecimal qty) {
        this.quotaRef = quotaRef;
        this.tradeId = tradeId;
        this.seq = seq;
        this.periodicity = periodicity;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.qty = qty;
    }

    public Long getId() { return id; }
    public String getQuotaRef() { return quotaRef; }
    public Long getTradeId() { return tradeId; }
    public int getSeq() { return seq; }
    public Periodicity getPeriodicity() { return periodicity; }
    public LocalDate getPeriodFrom() { return periodFrom; }
    public LocalDate getPeriodTo() { return periodTo; }
    public BigDecimal getQty() { return qty; }
}
