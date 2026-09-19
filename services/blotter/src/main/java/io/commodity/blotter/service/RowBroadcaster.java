package io.commodity.blotter.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.commodity.blotter.domain.RowChange;
import io.commodity.blotter.domain.RowPatch;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pushes row changes to open blotters, coalescing bursts.
 *
 * <p>Flow: the projector {@link #offer offers} changes; they collect in a pending map keyed by row; {@link #flush} (every 250 ms) sends ONE merged patch per
 * changed row to every subscriber of that row's desk. A row that changed nine times in a second is pushed once ({@link RowPatch#merge} guarantees the result
 * equals applying all nine). Each pushed event gets an increasing id and is kept in a bounded buffer, so a client that reconnects with {@code Last-Event-ID}
 * gets what it missed; if it has been away longer than the buffer holds, it is told to reset and refetch the whole table.
 *
 * <p>TARGET: the SSE gateway is its own tier that is itself a Kafka consumer, one consumer group per pod, filtering by each user's subscriptions; and it sits
 * behind HTTP/2 so browsers are not limited to six connections per origin. Here it lives inside the blotter service.
 */
public class RowBroadcaster {

    /** A connected blotter. Implementations write to an SSE connection and return false when it is gone. */
    public interface Subscriber {
        /** The desk this blotter shows, or null for all desks. */
        String deskId();

        boolean send(long eventId, String eventName, String json);
    }

    static final int BUFFER = 1_000;

    private record Buffered(long id, String deskId, String json) {}

    private final ObjectMapper json;
    private final Map<String, RowChange> pending = new LinkedHashMap<>();
    private final Deque<Buffered> buffer = new ArrayDeque<>();
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong();

    public RowBroadcaster(ObjectMapper json) {
        this.json = json;
    }

    /** Queues a change for the next flush, merging with any change of the same row already waiting. */
    public synchronized void offer(RowChange change) {
        if (change.isEmpty()) return;
        pending.merge(change.assignmentRef(), change, RowPatch::merge);
    }

    /** Sends everything pending, one event per row. Called by a timer; safe to call directly (tests). Returns how many events were sent. */
    public int flush() {
        List<RowChange> batch;
        synchronized (this) {
            if (pending.isEmpty()) return 0;
            batch = new ArrayList<>(pending.values());
            pending.clear();
        }
        for (RowChange change : batch) {
            long id = ids.incrementAndGet();
            String payload = write(change);
            synchronized (this) {
                buffer.addLast(new Buffered(id, change.deskId(), payload));
                if (buffer.size() > BUFFER) buffer.removeFirst();
            }
            deliver(id, change.deskId(), payload);
        }
        return batch.size();
    }

    /**
     * Registers a subscriber. With a {@code lastEventId}, first replays what it missed from the buffer, or sends {@code reset} if that is no longer possible.
     * Registration and replay happen together, so no event can fall between the replay and the live stream.
     */
    public synchronized void subscribe(Subscriber subscriber, Long lastEventId) {
        if (lastEventId != null) {
            long oldest = buffer.isEmpty() ? ids.get() + 1 : buffer.peekFirst().id();
            if (lastEventId + 1 < oldest) {
                subscriber.send(ids.get(), "reset", "{}");   // too far behind: refetch the table
            } else {
                for (Buffered b : buffer) {
                    if (b.id() > lastEventId && matches(subscriber, b.deskId())) subscriber.send(b.id(), "patch", b.json());
                }
            }
        }
        subscribers.add(subscriber);
    }

    public void unsubscribe(Subscriber subscriber) {
        subscribers.remove(subscriber);
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    private void deliver(long id, String deskId, String payload) {
        for (Subscriber s : subscribers) {
            if (!matches(s, deskId)) continue;
            if (!s.send(id, "patch", payload)) subscribers.remove(s);
        }
    }

    private static boolean matches(Subscriber s, String deskId) {
        return s.deskId() == null || s.deskId().equals(deskId);
    }

    private String write(RowChange change) {
        try {
            return json.writeValueAsString(change);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise row change", e);
        }
    }
}
