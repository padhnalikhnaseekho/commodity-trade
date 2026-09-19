package io.commodity.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.commodity.contracts.events.Topics;
import io.commodity.contracts.events.ValuationCompleted;
import io.commodity.contracts.events.ValuationRequested;
import io.commodity.contracts.valuation.Lane;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * A controllable stand-in for the valuation engine, running as its OWN Kafka consumer (not inside the gateway's context), so it survives a
 * gateway restart. Per request it can reply after a delay, hold the reply until released, drop it (a lost reply), or answer with an error.
 * It records every request it receives and how many were in flight per lane at once, which is how tests prove call counts and lane isolation.
 * The value it returns is simply quantity x 100.
 */
final class FakeEngine implements AutoCloseable {

    enum Kind { REPLY, HOLD, DROP, ERROR }

    record Action(Kind kind, long delayMs) {
        static Action reply(long delayMs) { return new Action(Kind.REPLY, delayMs); }
        static Action hold() { return new Action(Kind.HOLD, 0); }
        static Action drop() { return new Action(Kind.DROP, 0); }
        static Action error(long delayMs) { return new Action(Kind.ERROR, delayMs); }
    }

    final List<ValuationRequested> received = new CopyOnWriteArrayList<>();
    private final Map<Lane, AtomicInteger> inFlight = new EnumMap<>(Lane.class);
    private final Map<Lane, AtomicInteger> maxInFlight = new EnumMap<>(Lane.class);
    private final List<ValuationRequested> held = new CopyOnWriteArrayList<>();
    volatile Function<ValuationRequested, Action> behavior = r -> Action.reply(50);

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final KafkaProducer<String, String> producer;
    private final KafkaConsumer<String, String> consumer;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Thread poller;
    private volatile boolean running = true;

    FakeEngine(String bootstrap) {
        for (Lane l : Lane.values()) { inFlight.put(l, new AtomicInteger()); maxInFlight.put(l, new AtomicInteger()); }
        producer = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class, ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        consumer = new KafkaConsumer<>(Map.<String, Object>of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap, ConsumerConfig.GROUP_ID_CONFIG, "fake-engine-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest", ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(Topics.VALUATION_REQUEST));
        poller = new Thread(this::poll, "fake-engine");
        poller.start();
    }

    private void poll() {
        while (running) {
            try {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    ValuationRequested request = json.readValue(record.value(), ValuationRequested.class);
                    received.add(request);
                    handle(request);
                }
            } catch (Exception e) {
                if (running) throw new IllegalStateException(e);
            }
        }
    }

    private void handle(ValuationRequested request) {
        Action action = behavior.apply(request);
        int now = inFlight.get(request.lane()).incrementAndGet();
        maxInFlight.get(request.lane()).accumulateAndGet(now, Math::max);
        switch (action.kind()) {
            case REPLY, ERROR -> scheduler.schedule(() -> send(request, action.kind() == Kind.REPLY), action.delayMs(), TimeUnit.MILLISECONDS);
            case HOLD -> held.add(request);
            case DROP -> inFlight.get(request.lane()).decrementAndGet(); // the request is swallowed: no reply will ever come
        }
    }

    private void send(ValuationRequested request, boolean success) {
        try {
            var reply = success
                    ? new ValuationCompleted(request.requestId(), request.requestKey(), request.engine(), true, request.inputs().qty().multiply(BigDecimal.valueOf(100)).setScale(4).toPlainString(), null)
                    : new ValuationCompleted(request.requestId(), request.requestKey(), request.engine(), false, null, "fake engine error");
            producer.send(new ProducerRecord<>(Topics.VALUATION_REPLY, request.requestId().toString(), json.writeValueAsString(reply))).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            inFlight.get(request.lane()).decrementAndGet();
        }
    }

    /** Sends the reply of every held request now. */
    void releaseHeld() {
        List<ValuationRequested> toSend = new ArrayList<>(held);
        held.clear();
        toSend.forEach(r -> send(r, true));
    }

    /** Publishes an arbitrary message on the reply topic (a duplicate, a late reply, or garbage). */
    void publishReply(String key, String payload) throws Exception {
        producer.send(new ProducerRecord<>(Topics.VALUATION_REPLY, key, payload)).get();
    }

    long callsFor(String requestKey) {
        return received.stream().filter(r -> r.requestKey().equals(requestKey)).count();
    }

    int maxInFlight(Lane lane) {
        return maxInFlight.get(lane).get();
    }

    @Override
    public void close() throws Exception {
        running = false;
        poller.join(5_000);
        consumer.close();
        producer.close();
        scheduler.shutdownNow();
    }
}
