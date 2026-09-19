package io.commodity.pricing.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * THE SHARING TABLE (pricing.quota_revision_member): links a quota revision to the assignment
 * revisions in force at that revision, whether newly written or reused from earlier ones.
 *
 * <p>Why it exists: reading a quota as of a BRD is now a join (quota_revision -> this -> assignment_revision).
 * TRADEOFF: cheaper writes, slightly costlier reads; this is one row per assignment per revision, so
 * the number of link rows still grows with quota size even when little changed (measured in the P0.3 benchmark).
 */
@Entity
@Immutable
@Table(schema = "pricing", name = "quota_revision_member")
public class QuotaRevisionMember {

    @EmbeddedId
    private QuotaRevisionMemberId id;

    protected QuotaRevisionMember() {} // JPA

    public QuotaRevisionMember(QuotaRevisionMemberId id) {
        this.id = id;
    }

    public QuotaRevisionMemberId getId() { return id; }
}
