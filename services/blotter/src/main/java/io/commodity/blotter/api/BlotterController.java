package io.commodity.blotter.api;

import io.commodity.blotter.domain.RowView;
import io.commodity.blotter.service.BlotterProjector;
import io.commodity.blotter.service.RowBroadcaster;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The blotter's read API. {@code GET /api/blotter} returns the rows in force as of a BRD (default: live), so changing the as-of date re-resolves every row at
 * that date. {@code GET /api/blotter/stream} is a Server-Sent Events stream of row changes for the live view.
 *
 * <p>WHY SSE and not WebSockets: the data flows one way (server to browser), SSE is plain HTTP with automatic reconnection built into the browser, and
 * {@code Last-Event-ID} gives resume for free. The legacy platform also pushed desk state over server-sent events, so this is continuity, not novelty.
 */
@RestController
@RequestMapping("/api/blotter")
public class BlotterController {

    private final BlotterProjector projector;
    private final RowBroadcaster broadcaster;

    public BlotterController(BlotterProjector projector, RowBroadcaster broadcaster) {
        this.projector = projector;
        this.broadcaster = broadcaster;
    }

    @GetMapping
    public List<RowView> rows(@RequestParam(required = false) String deskId,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate brd) {
        return projector.rows(deskId, brd);
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String deskId, @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
        SseEmitter emitter = new SseEmitter(0L); // no server-side timeout: the client reconnects if the connection drops
        RowBroadcaster.Subscriber subscriber = new RowBroadcaster.Subscriber() {
            @Override public String deskId() { return deskId; }

            @Override public boolean send(long eventId, String eventName, String json) {
                try {
                    emitter.send(SseEmitter.event().id(Long.toString(eventId)).name(eventName).data(json, MediaType.APPLICATION_JSON));
                    return true;
                } catch (IOException | IllegalStateException e) {
                    return false; // the browser went away
                }
            }
        };
        emitter.onCompletion(() -> broadcaster.unsubscribe(subscriber));
        emitter.onTimeout(() -> broadcaster.unsubscribe(subscriber));
        emitter.onError(e -> broadcaster.unsubscribe(subscriber));
        broadcaster.subscribe(subscriber, lastEventId);
        // A first comment line makes the browser (and proxies) treat the stream as open immediately, before any row changes.
        subscriber.send(0, "open", "{}");
        return emitter;
    }
}
