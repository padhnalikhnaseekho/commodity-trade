package io.commodity.logistics.api;

/**
 * Request bodies of the logistics API. Quantities are strings (no float loss); changeKind is the caller-supplied
 * classification of the business event, required so the producer, not a heuristic, decides what is material.
 */
public final class AssignmentRequests {
    private AssignmentRequests() {}

    public record Add(String qty, String changeKind) {}

    public record Patch(String qty, String status, String changeKind) {}

    public record OperationalEvent(String changeKind) {}
}
