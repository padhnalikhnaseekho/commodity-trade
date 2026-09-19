package io.commodity.platform.outbox;

/** Where the publisher ships a row. Kafka in production code; a fake in tests. Must throw if delivery is not confirmed. */
public interface MessageSink {
    void send(OutboxRow row) throws Exception;
}
