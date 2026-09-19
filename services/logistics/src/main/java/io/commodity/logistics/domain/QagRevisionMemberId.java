package io.commodity.logistics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

/** Composite primary key (qagr_id, assignment_ref) of {@link QagRevisionMember}. */
@Embeddable
public record QagRevisionMemberId(
        @Column(name = "qagr_id") UUID qagrId,
        @Column(name = "assignment_ref") String assignmentRef) implements Serializable {}
