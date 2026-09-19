package io.commodity.platform.error;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A business-rule failure that maps to an HTTP problem response (RFC 7807, application/problem+json).
 *
 * <p>Domain code throws this (it is plain Java, no Spring) and a single advice in the web layer renders it. The
 * {@code details} map carries the numbers that caused the failure, e.g. requested vs available quantity. Cheap to
 * do and reads as production habit: support can diagnose from the response alone.
 */
public class DomainException extends RuntimeException {
    private final int status;
    private final String type;
    private final Map<String, Object> details = new LinkedHashMap<>();

    /** @param type short slug, rendered as https://commodity.demo/errors/{type} */
    public DomainException(int status, String type, String title) {
        super(title);
        this.status = status;
        this.type = type;
    }

    /** Adds one diagnostic field (chainable). Values are rendered as-is; pass quantities as strings. */
    public DomainException with(String key, Object value) {
        details.put(key, value);
        return this;
    }

    public int status() { return status; }
    public String type() { return type; }
    public Map<String, Object> details() { return details; }
}
