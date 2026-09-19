package io.commodity.pricing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

/** Composite primary key (pqr_id, par_id) of {@link QuotaRevisionMember}. */
@Embeddable
public record QuotaRevisionMemberId(
        @Column(name = "pqr_id") UUID pqrId,
        @Column(name = "par_id") UUID parId) implements Serializable {}
