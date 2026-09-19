package io.commodity.pricing.repository;

import io.commodity.pricing.domain.QuotaRevisionMember;
import io.commodity.pricing.domain.QuotaRevisionMemberId;
import org.springframework.data.jpa.repository.JpaRepository;

/** Insert/read access to the sharing table. Insert-only. */
public interface QuotaRevisionMemberRepository extends JpaRepository<QuotaRevisionMember, QuotaRevisionMemberId> {}
