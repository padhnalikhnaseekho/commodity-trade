package io.commodity.contracts.events;

/**
 * Topic names shared by producers and consumers. One place, so a rename is a compile error, not a silent mismatch.
 * Versioned in the name (.v1): a breaking payload change publishes to a new topic rather than mutating this one.
 *
 * <p>Keys (ordering is per key only): revision stream by quotaRef, operational stream by assignmentRef.
 * Never key by dealId: an energy desk-default deal would put a whole desk on a single partition.
 */
public final class Topics {
    private Topics() {}

    /** A new QAG revision was cut (material changes only). Key: quotaRef. */
    public static final String QAG_REVISION = "logistics.qag.revision.v1";
    /** Operational logistics events (load, discharge, ...). High volume, no QAG revision. Key: assignmentRef. */
    public static final String SHIPPING_OPERATIONAL = "logistics.shipping.operational.v1";

    /** A valuation request from the gateway to the engine adapter. Key: requestId. */
    public static final String VALUATION_REQUEST = "valuation.request.v1";
    /** The engine adapter's reply to the gateway. One shared reply topic, not one per gateway instance. Key: requestId. */
    public static final String VALUATION_REPLY = "valuation.reply.v1";
}
