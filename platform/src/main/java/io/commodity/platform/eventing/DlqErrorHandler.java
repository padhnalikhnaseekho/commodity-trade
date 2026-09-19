package io.commodity.platform.eventing;

import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * The shared consumer failure policy: retry a failing record a fixed number of times, then publish it to {@code <topic>.dlq} and move on.
 *
 * <p>WHY: a poison message must never stall its partition (everything behind it would wait forever). The DLQ keeps it for inspection and
 * replay; alerting on DLQ depth above zero turns a silent failure into a visible one.
 * TARGET: non-blocking retries on delay topics (5s, 30s, 5m) so even the retries do not hold the partition.
 */
public final class DlqErrorHandler {

    private DlqErrorHandler() {}

    public static DefaultErrorHandler create(KafkaTemplate<?, ?> kafka, long retryIntervalMs, long retries) {
        var recoverer = new DeadLetterPublishingRecoverer(kafka, (record, ex) -> new TopicPartition(record.topic() + ".dlq", -1));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(retryIntervalMs, retries));
    }
}
