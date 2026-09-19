package io.commodity.pricing.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Content hashes: the key to structural sharing.
 *
 * <p>Computed bottom-up, so a change anywhere in a subtree changes the root hash:
 * <pre>
 *   parameterHash  = sha256(element, value)
 *   componentHash  = sha256(kind, qty, fixedPrice, indexName, periodFrom, periodTo, formula, provisional)
 *   assignmentHash = sha256(assignmentRef, qty, sorted(parameterHashes), sorted(componentHashes))
 * </pre>
 * Equal hash means equal content, so an unchanged assignment can reuse its existing revision row instead of writing a
 * new copy of it and all its children. It is also what makes the two write strategies provably equivalent: resolving
 * either must yield the same hashes.
 *
 * <p>Details that matter (each one is a way this silently goes wrong):
 * <ul>
 *   <li>Children are SORTED before hashing, so list order can never create a false difference.</li>
 *   <li>Decimals use the fixed-scale canonical text, so 10.0 and 10.00 hash alike (see {@link Decimals}).</li>
 *   <li>Every field is length-prefixed, so ("ab","c") and ("a","bc") cannot collide by concatenation.</li>
 *   <li>A null field is distinct from an empty string.</li>
 *   <li>Each level carries a type tag, so a parameter and a component with equal fields cannot collide.</li>
 * </ul>
 * Plain Java, no Spring, no database (ContentHasherTest).
 */
public final class ContentHasher {

    private ContentHasher() {}

    public static String hash(ParameterContent p) {
        return digest("P", p.element(), Decimals.text(p.value()));
    }

    public static String hash(PriceComponentContent c) {
        return digest("C", c.kind().name(), Decimals.text(c.qty()), Decimals.text(c.fixedPrice()), c.indexName(),
                str(c.periodFrom()), str(c.periodTo()), c.formula(), Boolean.toString(c.provisional()));
    }

    /** Hash of a whole assignment subtree. Uses the already-sorted child lists of {@link AssignmentContent}. */
    public static String hash(AssignmentContent a) {
        return digest("A", Stream.concat(
                Stream.of(a.assignmentRef(), Decimals.text(a.qty()), "params"),
                Stream.concat(a.parameters().stream().map(ContentHasher::hash).sorted(),
                        Stream.concat(Stream.of("components"), a.components().stream().map(ContentHasher::hash).sorted())))
                .toArray(String[]::new));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String digest(String tag, String... fields) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            update(sha, tag);
            for (String f : fields) update(sha, f);
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed by the JDK", e);
        }
    }

    /** Length-prefixed field; a null is encoded as length -1, distinct from the empty string. */
    private static void update(MessageDigest sha, String field) {
        if (field == null) {
            sha.update("-1:".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        sha.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
        sha.update(bytes);
    }

    /** Convenience for tests and diagnostics: hashes of a collection of parameters, sorted. */
    static List<String> sortedParameterHashes(Collection<ParameterContent> parameters) {
        return parameters.stream().map(ContentHasher::hash).sorted().toList();
    }
}
