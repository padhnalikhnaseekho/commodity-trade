package io.commodity.gateway.domain;

import io.commodity.contracts.valuation.ValuationInputs;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * The idempotency key of a valuation: a deterministic hash of everything the answer depends on.
 *
 * <pre>
 *   requestKey = sha256( subjectRef | subjectLevel | brd | functionalLine | pqrId | parId(s)
 *                        | sorted(priceComponentIds) | sorted(parameterRevisionIds) | marketDataAsOf )
 * </pre>
 *
 * <p>WHY this works: pricing works off IMMUTABLE revisions, so a valuation is a pure function of its inputs. Every element of the key
 * is an immutable id or a date; nothing is mutable and nothing is derived at call time. Same key means the same answer, forever. That
 * is what makes retries free, collapses duplicate triggers, and makes replay a cache hit instead of a recomputation.
 *
 * <p>Detail: lists are sorted, so ordering cannot change the key; every field is length-prefixed, so no two different inputs can
 * concatenate to the same text. For a quota-level subject the parIds of all its assignments take the place of the single parId.
 *
 * <p>TRADEOFF worth knowing: the key includes the quota revision id (pqrId), so repricing ANY assignment of a quota changes the key of
 * every assignment in it, even though an untouched assignment's own ids (parId, components, parameters) are unchanged by structural
 * sharing. Dropping pqrId would let sharing carry over into the cache. It is kept because the spec defines the key that way.
 */
public final class RequestKey {

    private RequestKey() {}

    /**
     * @param marketDataAsOf the market data date the valuation is struck against. In P0 there is no market data, so callers pass the BRD.
     */
    public static String of(ValuationInputs inputs, String functionalLine, LocalDate marketDataAsOf) {
        List<String> parts = new ArrayList<>();
        parts.add(inputs.subjectRef());
        parts.add(inputs.level().name());
        parts.add(inputs.brd().toString());
        parts.add(functionalLine);
        parts.add(String.valueOf(inputs.pqrId()));
        parts.add("par:" + join(inputs.assignments().stream().map(a -> a.parId()).toList()));
        parts.add("pc:" + join(inputs.assignments().stream().flatMap(a -> a.components().stream()).map(c -> c.pcId()).toList()));
        parts.add("ppr:" + join(inputs.assignments().stream().flatMap(a -> a.parameters().stream()).map(p -> p.pprId()).toList()));
        parts.add(marketDataAsOf.toString());
        return "sha256:" + digest(parts);
    }

    private static String join(List<UUID> ids) {
        return ids.stream().sorted(Comparator.naturalOrder()).map(UUID::toString).reduce((a, b) -> a + "," + b).orElse("");
    }

    private static String digest(List<String> fields) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String f : fields) {
                byte[] bytes = f.getBytes(StandardCharsets.UTF_8);
                sha.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
                sha.update(bytes);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed by the JDK", e);
        }
    }
}
