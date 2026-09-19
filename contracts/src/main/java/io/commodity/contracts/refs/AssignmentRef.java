package io.commodity.contracts.refs;

/**
 * Third level of the nested ref: "tradeNumber.quotaSeq.assignmentSeq", e.g. {@code 1.1.3}.
 *
 * <p>Created by the logistics service inside a quota. Assignment is the finest grain that
 * valuation and close of books operate at (lots sit below but are not modelled here).
 * Talking point: this is the key that structural sharing hangs off, since pricing decides
 * per assignment ref whether a revision row can be reused.
 */
public record AssignmentRef(QuotaRef quota, int seq) implements Comparable<AssignmentRef> {

    public AssignmentRef {
        if (quota == null) throw new IllegalArgumentException("quota required");
        if (seq < 1) throw new IllegalArgumentException("assignment seq must be >= 1: " + seq);
    }

    /** Parses "1.1.3". Rejects anything that is not exactly three numeric parts. */
    public static AssignmentRef parse(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 3) throw new IllegalArgumentException("not an assignment ref: " + text);
        QuotaRef quota = new QuotaRef(TradeRef.parse(parts[0]), QuotaRef.parseSeq(parts[1], text));
        return new AssignmentRef(quota, QuotaRef.parseSeq(parts[2], text));
    }

    @Override
    public int compareTo(AssignmentRef o) {
        int c = quota.compareTo(o.quota);
        return c != 0 ? c : Integer.compare(seq, o.seq);
    }

    @Override
    public String toString() {
        return quota + "." + seq;
    }
}
