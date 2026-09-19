package io.commodity.contracts.refs;

/**
 * Second level of the nested ref: "tradeNumber.quotaSeq", e.g. {@code 1.2}.
 *
 * <p>Created by the trade service when it derives quotas from delivery terms; the quota
 * is also the unit that pricing revisions and QAG revisions are keyed on (see the
 * quota_ref columns in the logistics and pricing schemas). Talking point: the quota ref
 * is the aggregate key for the whole revision chain, which is why the QAG topic is keyed
 * by it (per-quota ordering).
 */
public record QuotaRef(TradeRef trade, int seq) implements Comparable<QuotaRef> {

    public QuotaRef {
        if (trade == null) throw new IllegalArgumentException("trade required");
        if (seq < 1) throw new IllegalArgumentException("quota seq must be >= 1: " + seq);
    }

    /** Parses "1.2". Rejects anything that is not exactly two numeric parts. */
    public static QuotaRef parse(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 2) throw new IllegalArgumentException("not a quota ref: " + text);
        return new QuotaRef(TradeRef.parse(parts[0]), parseSeq(parts[1], text));
    }

    /** Child assignment ref, e.g. quota 1.1 + seq 2 = "1.1.2". */
    public AssignmentRef assignment(int seq) {
        return new AssignmentRef(this, seq);
    }

    static int parseSeq(String part, String whole) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a ref: " + whole, e);
        }
    }

    @Override
    public int compareTo(QuotaRef o) {
        int c = trade.compareTo(o.trade);
        return c != 0 ? c : Integer.compare(seq, o.seq);
    }

    @Override
    public String toString() {
        return trade + "." + seq;
    }
}
