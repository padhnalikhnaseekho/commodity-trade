package io.commodity.platform.error;

import java.util.Arrays;

/** Parsing helpers that turn malformed client input into a 400 {@link DomainException} naming the offending field. */
public final class Parse {
    private Parse() {}

    /** Parses an enum by exact name; on failure the response lists the allowed values. */
    public static <E extends Enum<E>> E enumValue(Class<E> type, String text, String field) {
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DomainException(400, "invalid-request", "Invalid value for " + field)
                    .with("field", field).with("value", String.valueOf(text)).with("allowed", Arrays.toString(type.getEnumConstants()));
        }
    }
}
