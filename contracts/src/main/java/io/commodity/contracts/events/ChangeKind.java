package io.commodity.contracts.events;

/**
 * Kind of change logistics stamps on what it publishes. The taxonomy is split by one business question:
 * can this change move a valuation?
 *
 * <p>MATERIAL kinds cut a QAG revision (QAGR). OPERATIONAL kinds do not. The producer classifies, so consumers
 * never deserialise events they will discard, and the filter is visible as topic lag instead of being buried in
 * consumer code. Talking point: "producer-annotated, consumer-trusting, deterministic, not heuristic".
 *
 * <p>Open business question kept visible: load and discharge change physical quantity, so whether they are truly
 * non-material should be confirmed with the business before this classification is treated as final.
 */
public enum ChangeKind {
    // Material: each of these cuts a new QAG revision.
    TITLE_TRANSFER(true),
    RISK_TRANSFER(true),
    ALLOCATION(true),
    SPLIT(true),
    UNDO_SPLIT(true),
    MERGE(true),
    BLEND(true),
    // Operational: published on the shipping topic only; never cut a revision.
    LOAD(false),
    DISCHARGE(false),
    BILL_OF_LADING(false),
    INSURANCE_TRANSFER(false);

    private final boolean material;

    ChangeKind(boolean material) {
        this.material = material;
    }

    public boolean isMaterial() {
        return material;
    }
}
