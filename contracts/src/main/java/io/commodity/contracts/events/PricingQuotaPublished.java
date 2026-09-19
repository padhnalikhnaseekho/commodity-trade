package io.commodity.contracts.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Published on {@code pricing.quota.published.v1} (key: quotaRef) by pricing every time a quota's pricing changes: a new pricing revision, or an
 * approval decision. It is the FULL snapshot of the quota as pricing sees it at that moment, so a consumer (the blotter) can rebuild its rows without
 * reading back from pricing.
 *
 * <p>Derived values (priced, unpriced, over-fixed) are computed by pricing and sent; they are never stored by pricing, and the blotter stores them only
 * because it IS the materialised read model. {@code pqrId} is the revision the snapshot came from (an approval re-publishes the same revision).
 */
public record PricingQuotaPublished(String quotaRef, UUID pqrId, LocalDate brd, String cause, List<Assignment> assignments) {

    public record Assignment(String assignmentRef,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal qty,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal pricedQty,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unpricedQty,
                             boolean overFixed,
                             String approvalStatus) {}
}
