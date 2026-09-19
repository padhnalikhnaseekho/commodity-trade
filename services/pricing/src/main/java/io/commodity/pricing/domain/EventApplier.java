package io.commodity.pricing.domain;

import io.commodity.contracts.events.QagRevisionEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a QAG revision event into the assignment contents of the next pricing revision.
 *
 * <p>The event's {@code members} are the truth for WHICH assignments exist and their quantities: the consumer never reads
 * back from logistics. For each member: an existing assignment keeps its parameters and price components (only its
 * quantity is updated); a new one starts empty (nothing priced yet); one no longer listed is dropped.
 *
 * <p>Worked example: current {1.1.1 (2 components), 1.1.2}, event members {1.1.1 qty 120, 1.1.3 qty 50} gives
 * {1.1.1 with qty 120 and its 2 components, 1.1.3 empty}; 1.1.2 is dropped.
 *
 * <p>If a quantity falls below the priced quantity (a race the logistics-side fixation check cannot fully close), the
 * event is still applied and the assignment reports {@code overFixed()}. Rejecting it would leave pricing permanently
 * behind logistics, which is worse than flagging the anomaly.
 */
public final class EventApplier {

    private EventApplier() {}

    public static List<AssignmentContent> apply(List<AssignmentContent> current, QagRevisionEvent event) {
        Map<String, AssignmentContent> byRef = new LinkedHashMap<>();
        current.forEach(c -> byRef.put(c.assignmentRef(), c));
        return event.members().stream().map(m -> {
            AssignmentContent existing = byRef.get(m.assignmentRef());
            return existing == null ? AssignmentContent.empty(m.assignmentRef(), m.qty()) : existing.withQty(m.qty());
        }).toList();
    }
}
