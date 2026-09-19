package io.commodity.pricing.domain;

import io.commodity.platform.error.DomainException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fixed-scale decimals: quantities at scale 4, prices and parameter values at scale 6 (project style rule).
 *
 * <p>WHY fixed scale and not "strip trailing zeros": 10.0 and 10.00 must be the same value to the hash, to equality and to
 * the database, and a fixed scale gives one canonical text form ("10.0000") with no exponent notation. Input with more
 * precision than the scale is refused rather than silently rounded, because rounding a price or quantity is a business
 * decision. BigDecimal.equals is scale-sensitive, so normalising once at construction makes record equality reliable.
 */
public final class Decimals {

    public static final int QTY_SCALE = 4;
    public static final int PRICE_SCALE = 6;

    private Decimals() {}

    public static BigDecimal qty(BigDecimal v) {
        return scale(v, QTY_SCALE);
    }

    public static BigDecimal price(BigDecimal v) {
        return scale(v, PRICE_SCALE);
    }

    /** Null stays null: several price component fields are optional depending on the kind. */
    private static BigDecimal scale(BigDecimal v, int scale) {
        if (v == null) return null;
        try {
            return v.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new DomainException(400, "invalid-decimal", "Value has more than " + scale + " decimal places")
                    .with("value", v.toPlainString());
        }
    }

    /** Canonical text used by hashing: plain notation at the fixed scale; null becomes the empty marker handled by the hasher. */
    static String text(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }
}
