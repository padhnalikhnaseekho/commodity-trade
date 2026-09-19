package io.commodity.contracts.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Published on topic {@code logistics.qag.revision.v1} (key: quotaRef) each time a QAG revision is cut.
 *
 * <p>Why it carries the diff: logistics already knows what changed because it just cut the revision. If every
 * consumer re-derived the diff, that is duplicated work and a source of divergence when two consumers interpret it
 * differently. Legacy consumers diffed old against new revisions themselves; here the answer is in the event.
 * Pricing uses assignmentsModified/Added to decide which subtrees to rewrite, which is what makes structural
 * sharing cheap to implement.
 *
 * <p>{@code members} is the FULL membership at this revision, so a consumer can rebuild state without reading back.
 * Quantities are serialised as strings to avoid float loss (API rule). Refs are strings (nested text refs).
 */
public record QagRevisionEvent(
        UUID qagrId,
        UUID previousQagrId,
        String quotaRef,
        LocalDate brd,
        List<ChangeKind> changeKinds,
        List<String> assignmentsAdded,
        List<String> assignmentsRemoved,
        List<String> assignmentsModified,
        List<Member> members) {

    /** One assignment as it stands inside the revision. */
    public record Member(String assignmentRef, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal qty) {}
}
