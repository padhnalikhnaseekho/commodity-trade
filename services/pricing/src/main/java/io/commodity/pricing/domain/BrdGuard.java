package io.commodity.pricing.domain;

import io.commodity.platform.error.DomainException;
import java.time.LocalDate;

/**
 * INVARIANT: revisions of a closed business date are frozen. Once a desk's BRD advances past D, no revision with brd = D may be
 * written (BrdGuardTest). This is what keeps a past P&amp;L number reproducible: nothing can be added to a closed date.
 *
 * <p>TARGET: enforced twice, by making the closed partition read-only in the database and by the valuation gateway's BRD check.
 * The demo enforces the same rule at the write path with a 409, which is simpler and behaves the same.
 */
public final class BrdGuard {

    private BrdGuard() {}

    public static void requireOpen(String quotaRef, LocalDate revisionBrd, LocalDate deskBrd) {
        if (revisionBrd.isBefore(deskBrd)) {
            throw new DomainException(409, "brd-closed", "The business date of this revision is closed for the desk")
                    .with("quotaRef", quotaRef).with("revisionBrd", revisionBrd.toString()).with("deskBrd", deskBrd.toString());
        }
    }
}
