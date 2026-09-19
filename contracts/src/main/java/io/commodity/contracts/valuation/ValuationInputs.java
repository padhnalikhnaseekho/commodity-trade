package io.commodity.contracts.valuation;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything the valuation of a subject depends on, assembled from pricing's IMMUTABLE revisions as of a business date.
 *
 * <p>Why ids everywhere: a valuation is a pure function of its inputs, because pricing works off immutable revisions. Every
 * input carries the id of the row it came from (pqrId, parId, price component ids, parameter revision ids), which is what the
 * request key is built from. Same ids, same answer, forever. With structural sharing an unchanged assignment keeps its ids across
 * quota revisions, so the ids identify content, not the moment it was read.
 *
 * <p>The engine adapter receives this whole structure and performs no lookups of its own.
 */
public record ValuationInputs(String subjectRef, SubjectLevel level, LocalDate brd, UUID pqrId, UUID qagrId, String businessLine,
                              List<AssignmentInputs> assignments) {

    public record AssignmentInputs(String assignmentRef, UUID parId, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal qty,
                                   boolean approved, List<ComponentInput> components, List<ParameterInput> parameters) {}

    public record ComponentInput(UUID pcId, String kind, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal qty,
                                 @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal fixedPrice, String indexName,
                                 LocalDate periodFrom, LocalDate periodTo, String formula, boolean provisional) {}

    public record ParameterInput(UUID pprId, String element, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal value) {}
}
