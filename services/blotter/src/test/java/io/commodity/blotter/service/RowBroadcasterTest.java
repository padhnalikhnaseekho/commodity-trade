package io.commodity.blotter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.commodity.blotter.domain.RowChange;
import io.commodity.blotter.domain.RowChange.PatchOp;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The broadcaster's coalescing, desk filtering and resume behaviour, with no database and no HTTP. */
class RowBroadcasterTest {

    private record Sent(long id, String name, String json) {}

    private static final class Capture implements RowBroadcaster.Subscriber {
        final String desk;
        final List<Sent> sent = new ArrayList<>();
        Capture(String desk) { this.desk = desk; }
        @Override public String deskId() { return desk; }
        @Override public boolean send(long id, String name, String json) { sent.add(new Sent(id, name, json)); return true; }
    }

    private static RowChange replace(String ref, String desk, String field, String value) {
        return new RowChange(ref, desk, List.of(new PatchOp("replace", "/" + field, value)));
    }

    private final RowBroadcaster broadcaster = new RowBroadcaster(new ObjectMapper());

    // PROVES coalescing: a row that changed several times within the window is pushed ONCE, carrying the last value of each field.
    @Test
    void aBurstOnOneRowIsPushedOnceWithTheLastValues() {
        Capture blotter = new Capture(null);
        broadcaster.subscribe(blotter, null);

        broadcaster.offer(replace("1.1.1", "DESK-1", "pricedQty", "10.0000"));
        broadcaster.offer(replace("1.1.1", "DESK-1", "pricedQty", "20.0000"));
        broadcaster.offer(replace("1.1.1", "DESK-1", "pricedQty", "30.0000"));
        broadcaster.offer(replace("1.1.2", "DESK-1", "pricedQty", "5.0000"));

        assertThat(broadcaster.flush()).isEqualTo(2);           // two rows, not four changes
        assertThat(blotter.sent).hasSize(2);
        assertThat(blotter.sent.get(0).json()).contains("\"1.1.1\"").contains("30.0000").doesNotContain("10.0000").doesNotContain("20.0000");
        assertThat(broadcaster.flush()).isZero();               // nothing left pending
    }

    @Test
    void aBlotterOnlyReceivesItsOwnDesk() {
        Capture desk1 = new Capture("DESK-1"), desk2 = new Capture("DESK-2"), all = new Capture(null);
        broadcaster.subscribe(desk1, null);
        broadcaster.subscribe(desk2, null);
        broadcaster.subscribe(all, null);

        broadcaster.offer(replace("1.1.1", "DESK-1", "qty", "1.0000"));
        broadcaster.flush();

        assertThat(desk1.sent).hasSize(1);
        assertThat(desk2.sent).isEmpty();
        assertThat(all.sent).hasSize(1);
    }

    // PROVES resume: a client that reconnects with Last-Event-ID gets exactly what it missed, in order, and then continues live.
    @Test
    void aReconnectingClientIsReplayedWhatItMissed() {
        broadcaster.offer(replace("1.1.1", "DESK-1", "qty", "1.0000"));
        broadcaster.flush();                                  // event 1
        broadcaster.offer(replace("1.1.2", "DESK-1", "qty", "2.0000"));
        broadcaster.flush();                                  // event 2
        broadcaster.offer(replace("1.1.3", "DESK-1", "qty", "3.0000"));
        broadcaster.flush();                                  // event 3

        Capture reconnected = new Capture(null);
        broadcaster.subscribe(reconnected, 1L);               // it had seen event 1

        assertThat(reconnected.sent).extracting(Sent::id).containsExactly(2L, 3L);
        broadcaster.offer(replace("1.1.4", "DESK-1", "qty", "4.0000"));
        broadcaster.flush();
        assertThat(reconnected.sent).extracting(Sent::id).containsExactly(2L, 3L, 4L); // and it is live again
    }

    // PROVES the safety net: a client that has been away longer than the buffer holds is told to reset (refetch the table), not silently given a gap.
    @Test
    void aClientThatFellTooFarBehindIsToldToReset() {
        for (int i = 0; i < RowBroadcaster.BUFFER + 10; i++) {
            broadcaster.offer(replace("1.1." + (i + 1), "DESK-1", "qty", "1.0000"));
            broadcaster.flush();
        }
        Capture stale = new Capture(null);
        broadcaster.subscribe(stale, 1L);

        assertThat(stale.sent).hasSize(1);
        assertThat(stale.sent.get(0).name()).isEqualTo("reset");
    }

    @Test
    void aSubscriberThatDisconnectsIsDropped() {
        var gone = new RowBroadcaster.Subscriber() {
            @Override public String deskId() { return null; }
            @Override public boolean send(long id, String name, String json) { return false; }
        };
        broadcaster.subscribe(gone, null);
        assertThat(broadcaster.subscriberCount()).isEqualTo(1);

        broadcaster.offer(replace("1.1.1", "DESK-1", "qty", "1.0000"));
        broadcaster.flush();

        assertThat(broadcaster.subscriberCount()).isZero();
    }
}
