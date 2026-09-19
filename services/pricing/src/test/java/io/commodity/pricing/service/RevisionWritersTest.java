package io.commodity.pricing.service;

import static io.commodity.pricing.service.PricingTestDb.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.commodity.pricing.domain.AssignmentContent;
import io.commodity.pricing.repository.RevisionStore;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exit criteria of P0.3 against real Postgres, with the two writers side by side. Row counts are MEASURED, and the spec's
 * illustrative figures (960 / 48) were replaced by what the schema actually produces (see docs/SPEC-ADDENDUM.md).
 */
class RevisionWritersTest {

    private final RevisionStore store = PricingTestDb.store();
    private final CopyAllRevisionWriter copyAll = new CopyAllRevisionWriter(store);
    private final StructuralSharingRevisionWriter sharing = new StructuralSharingRevisionWriter(store);
    private static final LocalDate D1 = LocalDate.of(2026, 9, 1), D2 = LocalDate.of(2026, 9, 2), D3 = LocalDate.of(2026, 9, 3);

    // PROVES the headline, with real numbers. Quota of 20 assignments x (10 parameters + 3 components), change ONE:
    //   copy-all = 1 quota revision + 20 assignment revisions + 200 parameters + 60 components + 20 links = 301 rows
    //   sharing  = 1 quota revision + 1 new assignment revision + 10 + 3 children + 20 links            =  35 rows
    @Test
    void changingOneOfTwentyAssignmentsWritesFewerRowsUnderSharing() {
        String qa = freshQuotaRef(), qb = freshQuotaRef();
        var a0 = Fixtures.quota(qa, 20, 10, 3);
        var b0 = Fixtures.quota(qb, 20, 10, 3);

        assertThat(write(copyAll, qa, D1, a0).rowsWritten()).isEqualTo(301);   // first revision: everything is new
        assertThat(write(sharing, qb, D1, b0).rowsWritten()).isEqualTo(301);   // nothing to share yet, so the same

        long before = revisionRows();
        int copyRows = write(copyAll, qa, D2, Fixtures.mutateFirst(a0, 1)).rowsWritten();
        int shareRows = write(sharing, qb, D2, Fixtures.mutateFirst(b0, 1)).rowsWritten();

        assertThat(copyRows).isEqualTo(301);
        assertThat(shareRows).isEqualTo(35);
        assertThat(revisionRows() - before).isEqualTo(301 + 35); // the writers' own counts match the database's row counts
    }

    // PROVES the honest row of the benchmark: when everything changes, sharing saves nothing.
    @Test
    void whenEverythingChangesSharingSavesNothing() {
        String q = freshQuotaRef();
        var v0 = Fixtures.quota(q, 20, 10, 3);
        write(sharing, q, D1, v0);
        assertThat(write(sharing, q, D2, Fixtures.mutateFirst(v0, 20)).rowsWritten()).isEqualTo(301);
        // and partially: k changed = 21 fixed rows (quota revision + 20 links) + 14 per changed assignment
        String q2 = freshQuotaRef();
        var w0 = Fixtures.quota(q2, 20, 10, 3);
        write(sharing, q2, D1, w0);
        assertThat(write(sharing, q2, D2, Fixtures.mutateFirst(w0, 2)).rowsWritten()).isEqualTo(21 + 2 * 14);
        assertThat(write(sharing, q2, D3, Fixtures.mutateFirst(w0, 5)).rowsWritten()).isEqualTo(21 + 3 * 14); // 2 of the 5 already exist
    }

    // PROVES the test that matters, on a concrete case: resolving at the new revision gives IDENTICAL content under both
    // strategies. (The property test proves it over randomised histories.)
    @Test
    void resolutionIsIdenticalUnderBothStrategies() {
        String qa = freshQuotaRef(), qb = freshQuotaRef();
        var a0 = Fixtures.quota(qa, 20, 10, 3);
        var b0 = Fixtures.quota(qb, 20, 10, 3);
        write(copyAll, qa, D1, a0);
        write(sharing, qb, D1, b0);
        var pa = write(copyAll, qa, D2, Fixtures.mutateFirst(a0, 1));
        var pb = write(sharing, qb, D2, Fixtures.mutateFirst(b0, 1));

        List<AssignmentContent> ra = store.loadAssignments(pa.pqrId());
        List<AssignmentContent> rb = store.loadAssignments(pb.pqrId());

        assertThat(ra).hasSize(20).isEqualTo(Fixtures.mutateFirst(a0, 1));
        assertThat(rb).hasSize(20).isEqualTo(Fixtures.mutateFirst(b0, 1));
        assertThat(ra.stream().map(AssignmentContent::qty).toList()).isEqualTo(rb.stream().map(AssignmentContent::qty).toList());
    }

    // PROVES sharing really points at existing rows: unchanged assignments keep the SAME par_id across revisions,
    // and only the changed one gets a new one.
    @Test
    void unchangedAssignmentsReuseTheirRevisionRow() {
        String q = freshQuotaRef();
        var v0 = Fixtures.quota(q, 5, 2, 1);
        var first = write(sharing, q, D1, v0);
        var second = write(sharing, q, D2, Fixtures.mutateFirst(v0, 1));

        var ids1 = store.assignmentRevisionIds(first.pqrId());
        var ids2 = store.assignmentRevisionIds(second.pqrId());
        assertThat(ids2.get(q + ".1")).isNotEqualTo(ids1.get(q + ".1"));            // changed: new row
        for (int i = 2; i <= 5; i++) assertThat(ids2.get(q + "." + i)).isEqualTo(ids1.get(q + "." + i)); // unchanged: shared
    }

    // PROVES immutability in practice: later revisions never alter what an earlier revision resolves to, even though rows are shared.
    @Test
    void earlierRevisionsStillResolveToTheirOriginalContent() {
        String q = freshQuotaRef();
        var v0 = Fixtures.quota(q, 6, 3, 2);
        var first = write(sharing, q, D1, v0);
        write(sharing, q, D2, Fixtures.mutateFirst(v0, 3));
        write(sharing, q, D3, Fixtures.mutateFirst(v0, 6));

        assertThat(store.loadAssignments(first.pqrId())).isEqualTo(v0);
    }

    // PROVES DETERMINISM: resolving the same quota at the same BRD twice returns identical content, whatever happened since.
    @Test
    void resolvingTwiceAtTheSameBrdIsIdentical() {
        String q = freshQuotaRef();
        var v0 = Fixtures.quota(q, 4, 3, 2);
        write(sharing, q, D1, v0);
        write(sharing, q, D2, Fixtures.mutateFirst(v0, 2));

        var once = store.loadAssignments(store.asOf(q, D2).orElseThrow().pqrId());
        write(sharing, q, D3, Fixtures.mutateFirst(v0, 4)); // a later change must not disturb an earlier resolution
        var twice = store.loadAssignments(store.asOf(q, D2).orElseThrow().pqrId());

        assertThat(twice).isEqualTo(once);
    }

    // PROVES MONOTONICITY: revisions at BRD 1, 2 and 3; resolving at BRD 2 sees revision 2 and not revision 3.
    @Test
    void resolvingAtAnEarlierBrdNeverSeesALaterChange() {
        String q = freshQuotaRef();
        var v1 = Fixtures.quota(q, 3, 2, 1);
        var v2 = Fixtures.mutateFirst(v1, 1);
        var v3 = Fixtures.mutateFirst(v1, 3);
        var r1 = write(sharing, q, D1, v1);
        var r2 = write(sharing, q, D2, v2);
        var r3 = write(sharing, q, D3, v3);

        assertThat(store.asOf(q, D2).orElseThrow().pqrId()).isEqualTo(r2.pqrId()).isNotEqualTo(r3.pqrId());
        assertThat(store.loadAssignments(store.asOf(q, D2).orElseThrow().pqrId())).isEqualTo(v2);
        assertThat(store.asOf(q, D1).orElseThrow().pqrId()).isEqualTo(r1.pqrId());
        assertThat(store.asOf(q, LocalDate.of(2026, 8, 31))).isEmpty();          // before any revision
        assertThat(store.asOf(q, LocalDate.of(2026, 12, 1)).orElseThrow().pqrId()).isEqualTo(r3.pqrId()); // after: latest
    }

    // PROVES the tie-break: two revisions on the SAME BRD resolve to the later one, and the chain links them.
    @Test
    void twoRevisionsOnTheSameBrdResolveToTheLaterOne() {
        String q = freshQuotaRef();
        var v0 = Fixtures.quota(q, 3, 1, 1);
        var first = write(sharing, q, D1, v0);
        var second = write(sharing, q, D1, Fixtures.mutateFirst(v0, 1));

        assertThat(store.asOf(q, D1).orElseThrow().pqrId()).isEqualTo(second.pqrId());
        assertThat(second.previousPqrId()).isEqualTo(first.pqrId());
        assertThat(store.revisions(q)).extracting(RevisionStore.QuotaRevisionRow::pqrId).containsExactly(first.pqrId(), second.pqrId());
    }
}
