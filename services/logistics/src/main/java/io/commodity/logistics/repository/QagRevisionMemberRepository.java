package io.commodity.logistics.repository;

import io.commodity.logistics.domain.QagRevisionMember;
import io.commodity.logistics.domain.QagRevisionMemberId;
import org.springframework.data.jpa.repository.JpaRepository;

/** Access to logistics.qag_revision_member. */
public interface QagRevisionMemberRepository extends JpaRepository<QagRevisionMember, QagRevisionMemberId> {
    java.util.List<QagRevisionMember> findByIdQagrId(java.util.UUID qagrId);
}
